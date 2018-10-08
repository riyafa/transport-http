/*
 *  Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.wso2.transport.http.netty.message;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of the {@link OutboundThrottlingListener}.
 */
public class PassthroughOutboundThrottlingListener implements OutboundThrottlingListener {
    private static final Logger LOG = LoggerFactory.getLogger(HttpMessageDataStreamer.class);

    private Channel inChannel;
    private Channel outChannel;

    /**
     * Sets the incoming and outgoing message channels.
     *
     * @param inContext This will be used to block and resume read interest of the incoming channel.
     * @param outContext This will be used to check the writability of the outgoing channel.
     */
    public PassthroughOutboundThrottlingListener(ChannelHandlerContext inContext,
                                                 ChannelHandlerContext outContext) {
        inChannel = inContext.channel();
        outChannel = outContext.channel();
    }

    @Override
    public void onAcquire() {
        if (!outChannel.isWritable()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Read disabled for inChannel {}", inChannel.id());
            }
            inChannel.config().setAutoRead(false);
        }
    }

    @Override
    public void onRelease() {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Read enabled for inChannel {}", inChannel.id());
        }
        inChannel.config().setAutoRead(true);
    }
}
