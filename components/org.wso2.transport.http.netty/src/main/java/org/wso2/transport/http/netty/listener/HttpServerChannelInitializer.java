/*
 *  Copyright (c) 2017 WSO2 Inc. (http://wso2.com) All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.wso2.transport.http.netty.listener;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpServerUpgradeHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2ServerUpgradeCodec;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.ApplicationProtocolNegotiationHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.AsciiString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.messaging.CarbonTransportInitializer;
import org.wso2.transport.http.netty.common.Constants;
import org.wso2.transport.http.netty.common.ssl.SSLConfig;
import org.wso2.transport.http.netty.common.ssl.SSLHandlerFactory;
import org.wso2.transport.http.netty.config.ChunkConfig;
import org.wso2.transport.http.netty.config.KeepAliveConfig;
import org.wso2.transport.http.netty.config.RequestSizeValidationConfig;
import org.wso2.transport.http.netty.contract.ServerConnectorFuture;
import org.wso2.transport.http.netty.sender.CertificateValidationHandler;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SSLEngine;

/**
 * A class that responsible for build server side channels.
 */
public class HttpServerChannelInitializer extends ChannelInitializer<SocketChannel>
        implements CarbonTransportInitializer {

    private static final Logger log = LoggerFactory.getLogger(HttpServerChannelInitializer.class);

    private int socketIdleTimeout;
    private boolean httpTraceLogEnabled;
    private boolean httpAccessLogEnabled;
    private ChunkConfig chunkConfig;
    private KeepAliveConfig keepAliveConfig;
    private String interfaceId;
    private String serverName;
    private SSLConfig sslConfig;
    private ServerConnectorFuture serverConnectorFuture;
    private RequestSizeValidationConfig reqSizeValidationConfig;
    private boolean http2Enabled = false;
    private boolean validateCertEnabled;
    private int cacheDelay;
    private int cacheSize;
    private ChannelGroup allChannels;

    @Override
    public void setup(Map<String, String> parameters) {
    }

    @Override
    public void initChannel(SocketChannel ch) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("Initializing source channel pipeline");
        }

        ChannelPipeline serverPipeline = ch.pipeline();

        if (http2Enabled) {
            if (sslConfig != null) {
                SslContext sslCtx = new SSLHandlerFactory(sslConfig).createHttp2TLSContextForServer();
                serverPipeline.addLast(sslCtx.newHandler(ch.alloc()), new Http2PipelineConfiguratorForServer());
            } else {
                configureH2cPipeline(serverPipeline);
            }
        } else {
            if (sslConfig != null) {
            configureSslForHttp(serverPipeline);
            } else {
                configureHTTPPipeline(serverPipeline);
            }
        }
    }

    private void configureSslForHttp(ChannelPipeline serverPipeline) {

        SSLEngine sslEngine = new SSLHandlerFactory(sslConfig).buildServerSSLEngine();
            serverPipeline.addLast(Constants.SSL_HANDLER, new SslHandler(sslEngine));
            if (validateCertEnabled) {
                serverPipeline.addLast(Constants.HTTP_CERT_VALIDATION_HANDLER,
                        new CertificateValidationHandler(sslEngine, cacheDelay, cacheSize));
            }
            serverPipeline.addLast(Constants.TLS_COMPLETION_HANDLER,
                    new TLSHandshakeCompletionHandlerForServer(this, serverPipeline));
    }

    /**
     * Configure the pipeline if user sent HTTP requests
     *
     * @param serverPipeline Channel
     */
    void configureHTTPPipeline(ChannelPipeline serverPipeline) {

        if (!http2Enabled) {
            serverPipeline.addLast(Constants.HTTP_ENCODER, new HttpResponseEncoder());
        }
        serverPipeline.addLast("decoder",
                new HttpRequestDecoder(reqSizeValidationConfig.getMaxUriLength(),
                        reqSizeValidationConfig.getMaxHeaderSize(), reqSizeValidationConfig.getMaxChunkSize()));
        serverPipeline.addLast("compressor", new CustomHttpContentCompressor());
        serverPipeline.addLast("chunkWriter", new ChunkedWriteHandler());

        if (httpTraceLogEnabled) {
            serverPipeline.addLast(Constants.HTTP_TRACE_LOG_HANDLER,
                             new HTTPTraceLoggingHandler("tracelog.http.downstream"));
        }
        if (httpAccessLogEnabled) {
            serverPipeline.addLast(Constants.HTTP_ACCESS_LOG_HANDLER, new HttpAccessLoggingHandler("accesslog.http"));
        }
        serverPipeline.addLast("uriLengthValidator", new UriAndHeaderLengthValidator(this.serverName));
        if (reqSizeValidationConfig.getMaxEntityBodySize() > -1) {
            serverPipeline.addLast("maxEntityBodyValidator", new MaxEntityBodyValidator(this.serverName,
                    reqSizeValidationConfig.getMaxEntityBodySize()));
        }

        serverPipeline.addLast(Constants.WEBSOCKET_SERVER_HANDSHAKE_HANDLER,
                         new WebSocketServerHandshakeHandler(this.serverConnectorFuture, this.interfaceId));
        serverPipeline.addLast(Constants.HTTP_SOURCE_HANDLER,
                               new SourceHandler(this.serverConnectorFuture, this.interfaceId, this.chunkConfig,
                                                 keepAliveConfig, this.serverName, this.allChannels));
        if (socketIdleTimeout > 0) {
            serverPipeline.addBefore(
                    Constants.HTTP_SOURCE_HANDLER, Constants.IDLE_STATE_HANDLER,
                    new IdleStateHandler(socketIdleTimeout, socketIdleTimeout, socketIdleTimeout,
                            TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Configures HTTP/2 clear text pipeline.
     *
     * @param pipeline the channel pipeline
     */
    private void configureH2cPipeline(ChannelPipeline pipeline) {
        // Add http2 upgrade decoder and upgrade handler
        final HttpServerCodec sourceCodec = new HttpServerCodec();

        final HttpServerUpgradeHandler.UpgradeCodecFactory upgradeCodecFactory = protocol -> {
            if (AsciiString.contentEquals(Http2CodecUtil.HTTP_UPGRADE_PROTOCOL_NAME, protocol)) {
                return new Http2ServerUpgradeCodec(Constants.HTTP2_SOURCE_HANDLER, new Http2SourceHandlerBuilder(
                        this.interfaceId, this.serverConnectorFuture, serverName).build());
            } else {
                return null;
            }
        };
        pipeline.addLast(Constants.HTTP_ENCODER, sourceCodec);
        pipeline.addLast(Constants.HTTP2_UPGRADE_HANDLER,
                         new HttpServerUpgradeHandler(sourceCodec, upgradeCodecFactory, Integer.MAX_VALUE));
        /* Max size of the upgrade request is limited to 2GB. Need to see whether there is a better approach to handle
           large upgrade requests. Requests will be propagated to next handlers if no upgrade has been attempted */
        configureHTTPPipeline(pipeline);
    }

    @Override
    public boolean isServerInitializer() {
        return true;
    }

    public void setServerConnectorFuture(ServerConnectorFuture serverConnectorFuture) {
        this.serverConnectorFuture = serverConnectorFuture;
    }

    void setIdleTimeout(int idleTimeout) {
        this.socketIdleTimeout = idleTimeout;
    }

    void setHttpTraceLogEnabled(boolean httpTraceLogEnabled) {
        this.httpTraceLogEnabled = httpTraceLogEnabled;
    }

    void setHttpAccessLogEnabled(boolean httpAccessLogEnabled) {
        this.httpAccessLogEnabled = httpAccessLogEnabled;
    }

    void setInterfaceId(String interfaceId) {
        this.interfaceId = interfaceId;
    }

    void setSslConfig(SSLConfig sslConfig) {
        this.sslConfig = sslConfig;
    }

    void setReqSizeValidationConfig(RequestSizeValidationConfig reqSizeValidationConfig) {
        this.reqSizeValidationConfig = reqSizeValidationConfig;
    }

    public void setChunkingConfig(ChunkConfig chunkConfig) {
        this.chunkConfig = chunkConfig;
    }

    void setKeepAliveConfig(KeepAliveConfig keepAliveConfig) {
        this.keepAliveConfig = keepAliveConfig;
    }

    void setValidateCertEnabled(boolean validateCertEnabled) {
        this.validateCertEnabled = validateCertEnabled;
    }

    void setCacheDelay(int cacheDelay) {
        this.cacheDelay = cacheDelay;
    }

    void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    void setServerName(String serverName) {
        this.serverName = serverName;
    }

    /**
     * Sets whether HTTP/2.0 is enabled for the connection.
     *
     * @param http2Enabled whether HTTP/2.0 is enabled
     */
    void setHttp2Enabled(boolean http2Enabled) {
        this.http2Enabled = http2Enabled;
    }

    /**
     * Handler which handles ALPN.
     */
    class Http2PipelineConfiguratorForServer extends ApplicationProtocolNegotiationHandler {

        Http2PipelineConfiguratorForServer() {
            super(ApplicationProtocolNames.HTTP_1_1);
        }

        /**
         *  Configure pipeline after SSL handshake
         */
        @Override
        protected void configurePipeline(ChannelHandlerContext ctx, String protocol) throws Exception {
            if (ApplicationProtocolNames.HTTP_2.equals(protocol)) {
                // handles pipeline for HTTP/2 requests after SSL handshake
                ctx.pipeline().
                        addLast(Constants.HTTP2_SOURCE_HANDLER,
                                new Http2SourceHandlerBuilder(interfaceId, serverConnectorFuture, serverName).build());
            } else if (ApplicationProtocolNames.HTTP_1_1.equals(protocol)) {
                // handles pipeline for HTTP/1.x requests after SSL handshake
                configureHTTPPipeline(ctx.pipeline());
            } else {
                throw new IllegalStateException("unknown protocol: " + protocol);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (ctx != null && ctx.channel().isActive()) {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    void setAllChannels(ChannelGroup allChannels) {
        this.allChannels = allChannels;
    }
}
