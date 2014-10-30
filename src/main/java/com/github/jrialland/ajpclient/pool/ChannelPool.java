/* Copyright (c) 2014 Julien Rialland <julien.rialland@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.jrialland.ajpclient.pool;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.r358.poolnetty.common.BootstrapProvider;
import org.r358.poolnetty.common.ConnectionInfo;
import org.r358.poolnetty.common.ConnectionInfoProvider;
import org.r358.poolnetty.common.PoolProvider;
import org.r358.poolnetty.pool.NettyConnectionPool;
import org.r358.poolnetty.pool.NettyConnectionPoolBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jrialland.ajpclient.CPing;
import com.github.jrialland.ajpclient.Forward;

public class ChannelPool {

	private static final Logger LOGGER = LoggerFactory.getLogger(ChannelPool.class);

	private static final Logger getLog() {
		return LOGGER;
	}

	String host;

	int port;
	
	private NettyConnectionPool ncp;

	protected ChannelPool(final String host, final int port) throws Exception {
		this.host = host;
		this.port = port;
	
		NettyConnectionPoolBuilder ncb = new NettyConnectionPoolBuilder();
		
		final Bootstrap bootstrap = Channels.newBootStrap(host, port);
		
		ncb.withBootstrapProvider(new BootstrapProvider() {
			
			@Override
			public Bootstrap createBootstrap(PoolProvider pp) {
				return bootstrap;
			}
		});
		
		ncb.withConnectionInfoProvider(new ConnectionInfoProvider() {
			
			@Override
			public ConnectionInfo connectionInfo(PoolProvider pp) {
				final InetSocketAddress remoteAddr = new InetSocketAddress(host, port);
				return new ConnectionInfo(remoteAddr, null, new ChannelInitializer<Channel>() {
					@Override
					protected void initChannel(Channel ch) throws Exception {
						Channels.initChannel(ch);
					}
				});
			}
		});
		
		ncp = ncb.build();
		ncp.start(1000, TimeUnit.MILLISECONDS);
	}

	public void execute(final Forward forward) throws Exception {
		execute(forward.impl());
	}

	public void execute(final CPing cping) throws Exception {
		execute(cping.impl());
	}

	public void execute(final Forward forward, final boolean reuseConnection) throws Exception {
		execute(forward.impl(), reuseConnection);
	}

	public void execute(final CPing cping, final boolean reuseConnection) throws Exception {
		execute(cping.impl(), reuseConnection);
	}

	protected void execute(final ChannelCallback callback) throws Exception {
		execute(callback, true);
	}

	/**
	 * Handles channel picking/returning from/to the pool. the 3 methods
	 * {@link ChannelCallback#beforeUse(Channel)},
	 * {@link ChannelCallback#__doWithChannel(Channel)} and
	 * {@link ChannelCallback#beforeRelease(Channel)} are called in this order
	 * on the passed callback instance
	 *
	 * @param callback
	 *            a channelcallback
	 * @throws Exception
	 */
	protected void execute(final ChannelCallback callback, final boolean reuseConnection) throws Exception {
		getLog().debug("getting channel from the connection pool ...");
		final Channel channel = ncp.lease(1, TimeUnit.SECONDS, null);
		getLog().debug("... obtained " + channel);

		boolean reuse = false;
		try {
			callback.beforeUse(channel);
			reuse = callback.__doWithChannel(channel) && reuseConnection;
			try {
				callback.beforeRelease(channel);
			} catch (final Exception e) {
				getLog().warn("while releasing channel", e);
				reuse = false;
			}

		} finally {
			if (reuse) {
				getLog().debug("returning channel " + channel + " to the connection pool");
				ncp.yield(channel);
			} else {
				getLog().debug("invalidating channel " + channel);
				channel.close();
			}
		}
	}

	protected void destroy() {
		ncp.stop(true);
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}
}
