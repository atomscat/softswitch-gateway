/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.atomscat.freeswitch.esl.outbound.handler;

import com.atomscat.freeswitch.esl.exception.OutboundClientException;
import com.atomscat.freeswitch.esl.helper.EslHelper;
import com.atomscat.freeswitch.esl.outbound.listener.ChannelEventListener;
import com.atomscat.freeswitch.esl.transport.SendMsg;
import com.atomscat.freeswitch.esl.transport.event.EslEvent;
import com.atomscat.freeswitch.esl.transport.event.EslEventHeaderNames;
import com.atomscat.freeswitch.esl.transport.message.EslHeaders;
import com.atomscat.freeswitch.esl.transport.message.EslMessage;
import com.atomscat.freeswitch.esl.util.RemotingUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <p>OutboundChannelHandler class.</p>
 *
 * @author : <a href="everyone@aliyun.com">everyone</a>
 * @version 1.0.0
 */
@Slf4j
public class OutboundChannelHandler extends SimpleChannelInboundHandler<EslMessage> {

    private static final String MESSAGE_TERMINATOR = "\n\n";
    private static final String LINE_TERMINATOR = "\n";

    private final Lock syncLock = new ReentrantLock();
    private final ChannelEventListener listener;
    private final ExecutorService publicExecutor;
    private final ConcurrentHashMap<String, CompletableFuture<EslEvent>> backgroundJobs =
            new ConcurrentHashMap<>();

    private final ExecutorService backgroundJobExecutor = new ScheduledThreadPoolExecutor(8,
            new DefaultThreadFactory("Outbound-BackgroundJob-Executor", true));
    /**
     * 这是保证事件接收顺序的单线程池
     */
    private final ExecutorService onEslEventExecutor;

    /**
     * 这是用于并发处理onConnect的多线程池
     */
    private final ExecutorService onConnectExecutor;

    private final boolean isTraceEnabled = log.isTraceEnabled();
    private final ConcurrentLinkedQueue<CompletableFuture<EslMessage>> apiCalls = new ConcurrentLinkedQueue<>();
    private Channel channel;
    private String remoteAddr;


    /**
     * Constructor for OutboundChannelHandler.
     *
     * @param listener       a {@link ChannelEventListener} object.
     * @param publicExecutor a {@link ExecutorService} object.
     */
    public OutboundChannelHandler(ChannelEventListener listener, ExecutorService publicExecutor, ExecutorService onEslEventExecutor, ExecutorService onConnectExecutor) {
        this.listener = listener;
        this.publicExecutor = publicExecutor;
        this.onEslEventExecutor = onEslEventExecutor;
        this.onConnectExecutor = onConnectExecutor;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.channel = ctx.channel();
        this.remoteAddr = RemotingUtil.socketAddress2String(channel.remoteAddress());

        sendApiSingleLineCommand(ctx.channel(), "connect")
                .thenAcceptAsync(response -> {
                    //这里改为线程池执行
                    onConnectExecutor.execute(() -> {
                        EslEvent eslEvent = new EslEvent(response, true);
                        listener.onConnect(
                                new Context(ctx.channel(), OutboundChannelHandler.this, 120),
                                eslEvent
                        );
                    });
                }, publicExecutor)
                .exceptionally(throwable -> {
                    log.error("Outbound Error", throwable);
                    ctx.channel().close();
                    listener.handleDisconnectNotice(remoteAddr, ctx);
                    return null;
                });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        log.debug("channelInactive remoteAddr : {}", remoteAddr);
//        listener.onChannelClosed(remoteAddr);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
        if (evt instanceof IdleStateEvent) {
            if (((IdleStateEvent) evt).state() == IdleState.READER_IDLE) {
                log.debug("userEventTriggered remoteAddr : {}, evt state : {} ", remoteAddr, ((IdleStateEvent) evt).state());
                publicExecutor.execute(() -> {
                    try {
                        sendAsyncCommand(ctx.channel(), "bgapi status");
                    } catch (Exception e) {
                        log.error("user event triggered error", e);
                        Thread.currentThread().interrupt();
                        throw new OutboundClientException(String.format("user event triggered error remoteAddr : %s", remoteAddr), e);
                    }
                });
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("exceptionCaught remoteAddr : {}, cause : ", remoteAddr, cause);

        for (final CompletableFuture<EslMessage> apiCall : apiCalls) {
            apiCall.completeExceptionally(cause.getCause());
        }

        for (final CompletableFuture<EslEvent> backgroundJob : backgroundJobs.values()) {
            backgroundJob.completeExceptionally(cause.getCause());
        }

        ctx.close();

        ctx.fireExceptionCaught(cause);
    }


    @Override
    protected void channelRead0(ChannelHandlerContext ctx, EslMessage message) throws Exception {
        final String contentType = message.getContentType();
        if (contentType.equals(EslHeaders.Value.TEXT_EVENT_PLAIN) ||
                contentType.equals(EslHeaders.Value.TEXT_EVENT_XML)) {
            //  transform into an event
            final EslEvent eslEvent = new EslEvent(message);
            if ("BACKGROUND_JOB".equals(eslEvent.getEventName())) {
                final String backgroundUuid = eslEvent.getEventHeaders().get(EslEventHeaderNames.JOB_UUID);
                final CompletableFuture<EslEvent> future = backgroundJobs.remove(backgroundUuid);
                if (null != future) {
                    future.complete(eslEvent);
                }
            } else {
                handleEslEvent(ctx, eslEvent);
            }
        } else {
            handleEslMessage(ctx, message);
        }
    }

    protected void handleEslEvent(final ChannelHandlerContext ctx, final EslEvent event) {
        onEslEventExecutor.execute(() -> listener.handleEslEvent(
                new Context(ctx.channel(), OutboundChannelHandler.this, 120), event));
    }

    protected void handleEslMessage(ChannelHandlerContext ctx, EslMessage message) {
        log.info("Received message: [{}]", message);
        final String contentType = message.getContentType();

        switch (contentType) {
            case EslHeaders.Value.API_RESPONSE:
                log.debug("Api response received [{}]", message);
                if (apiCalls.size() > 0) {
                    apiCalls.poll().complete(message);
                }
                break;

            case EslHeaders.Value.COMMAND_REPLY:
                log.debug("Command reply received [{}]", message);
                if (apiCalls.size() > 0) {
                    apiCalls.poll().complete(message);
                }
                break;

            case EslHeaders.Value.AUTH_REQUEST:
                log.error("Auth request received [{}]", message);
                listener.handleAuthRequest(ctx);
                break;

            case EslHeaders.Value.TEXT_DISCONNECT_NOTICE:
                log.error("Disconnect notice received [{}]", message);
                listener.handleDisconnectNotice(remoteAddr, ctx);
                break;

            default:
                log.error("Unexpected message content type [{}]", contentType);
                break;
        }
    }


    /**
     * @param channel     a {@link Channel} object.
     * @param sendMsgList a {@link List} object.
     */
    public void sendAsyncMultiSendMsgCommand(Channel channel, final List<SendMsg> sendMsgList) {
        //  Build command with double line terminator at the end
        StringBuilder sb = new StringBuilder();
        for (SendMsg sendMsg : sendMsgList) {
            for (String line : sendMsg.getMsgLines()) {
                sb.append(line);
                sb.append(LINE_TERMINATOR);
            }
            sb.append(LINE_TERMINATOR);
        }
        syncLock.lock();
        try {
            channel.writeAndFlush(sb.toString());
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * 异步
     *
     * @param channel      a {@link Channel} object.
     * @param commandLines a {@link List} object.
     */
    public void sendAsyncMultiLineCommand(Channel channel, final List<String> commandLines) {
        //  Build command with double line terminator at the end
        StringBuilder sb = new StringBuilder();
        for (String line : commandLines) {
            sb.append(line);
            sb.append(LINE_TERMINATOR);
        }
        sb.append(LINE_TERMINATOR);
        syncLock.lock();
        try {
            channel.writeAndFlush(sb.toString());
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * @param channel a {@link Channel} object.
     * @param command a {@link String} String.
     * @return a {@link CompletableFuture} object.
     */
    public CompletableFuture<EslMessage> sendApiSingleLineCommand(Channel channel, final String command) {
        final CompletableFuture<EslMessage> future = new CompletableFuture<>();
        syncLock.lock();
        try {
            channel.writeAndFlush(command + MESSAGE_TERMINATOR);
            apiCalls.add(future);
        } finally {
            syncLock.unlock();
        }
        return future;
    }

    public CompletableFuture<EslMessage> sendApiMultiLineCommand(Channel channel, final List<String> commandLines) {
        //  Build command with double line terminator at the end
        final StringBuilder sb = new StringBuilder();
        for (final String line : commandLines) {
            sb.append(line);
            sb.append(LINE_TERMINATOR);
        }
        sb.append(LINE_TERMINATOR);
        syncLock.lock();
        try {
            final CompletableFuture<EslMessage> future = new CompletableFuture<>();
            channel.writeAndFlush(sb.toString());
            apiCalls.add(future);
            return future;
        } finally {
            syncLock.unlock();
        }
    }

    public CompletableFuture<EslMessage> sendApiMultiSendMsgCommand(Channel channel, final List<SendMsg> sendMsgList) {
        //  Build command with double line terminator at the end
        final StringBuilder sb = new StringBuilder();
        for (SendMsg sendMsg : sendMsgList) {
            for (final String line : sendMsg.getMsgLines()) {
                sb.append(line);
                sb.append(LINE_TERMINATOR);
            }
            sb.append(LINE_TERMINATOR);
        }
        syncLock.lock();
        try {
            final CompletableFuture<EslMessage> future = new CompletableFuture<>();
            channel.writeAndFlush(sb.toString());
            apiCalls.add(future);
            return future;
        } finally {
            syncLock.unlock();
        }
    }

    /**
     * Returns the Job UUID of that the response event will have.
     *
     * @param command cmd
     * @return Job-UUID as a string
     */
    public String sendAsyncCommand(Channel channel, final String command) {
        /*
         * Send synchronously to get the Job-UUID to return, the results of the actual
         * job request will be returned by the server as an async event.
         */
        CompletableFuture<EslMessage> messageCompletableFuture = sendApiSingleLineCommand(channel, command);
        if (isTraceEnabled) {
            log.trace("sendAsyncCommand command : {}, response : {}", command, messageCompletableFuture);
        }
        try {
            EslMessage response = messageCompletableFuture.get();
            if (response.hasHeader(EslHeaders.Name.JOB_UUID)) {
                return response.getHeaderValue(EslHeaders.Name.JOB_UUID);
            } else {
                log.warn("sendAsyncCommand command : {}, response : {}", command, EslHelper.formatEslMessage(response));
                throw new IllegalStateException("Missing Job-UUID header in bgapi response");
            }
        } catch (InterruptedException e) {
            log.error("sendAsyncCommand interruptedException error", e);
            Thread.currentThread().interrupt();
            throw new OutboundClientException(String.format("InterruptedException error command : %s", command), e);
        } catch (ExecutionException e) {
            throw new OutboundClientException(String.format("ExecutionException error command : %s", command), e);
        }
    }

    public CompletableFuture<EslEvent> sendBackgroundApiCommand(Channel channel, final String command) {
        return sendApiSingleLineCommand(channel, command)
                .thenComposeAsync(result -> {
                    if (result.hasHeader(EslHeaders.Name.JOB_UUID)) {
                        final String jobId = result.getHeaderValue(EslHeaders.Name.JOB_UUID);
                        final CompletableFuture<EslEvent> resultFuture = new CompletableFuture<>();
                        backgroundJobs.put(jobId, resultFuture);
                        return resultFuture;
                    } else {
                        final CompletableFuture<EslEvent> resultFuture = new CompletableFuture<>();
                        resultFuture.completeExceptionally(new IllegalStateException("Missing Job-UUID header in bgapi response"));
                        return resultFuture;
                    }
                }, backgroundJobExecutor);
    }

    /**
     * <p>close.</p>
     *
     * @return a {@link ChannelFuture} object.
     */
    public ChannelFuture close() {
        return channel.close();
    }


}
