package com.redant.cluster.master;

import com.redant.cluster.service.discover.ZkServiceDiscover;
import com.redant.core.common.constants.CommonConstants;
import com.redant.core.server.Server;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpContentCompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * MasterServer
 * @author houyi.wh
 * @date 2017/11/20
 */
public final class MasterServer implements Server {

    private static final Logger LOGGER = LoggerFactory.getLogger(MasterServer.class);

    private String zkAddress;

    public MasterServer(String zkAddress){
        this.zkAddress = zkAddress;
    }

    @Override
    public void preStart() {
        // 监听SlaveNode的变化
        ZkServiceDiscover.getInstance(zkAddress).watch();
    }

    @Override
    public void start() {
        EventLoopGroup bossGroup = new NioEventLoopGroup(CommonConstants.BOSS_GROUP_SIZE, new DefaultThreadFactory("boss", true));
        EventLoopGroup workerGroup = new NioEventLoopGroup(CommonConstants.WORKER_GROUP_SIZE, new DefaultThreadFactory("worker", true));
        try {
            long start = System.currentTimeMillis();
            ServerBootstrap b = new ServerBootstrap();
            b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup)
             .channel(NioServerSocketChannel.class)
//             .handler(new LoggingHandler(LogLevel.INFO))
             .childHandler(new MasterServerInitializer(zkAddress));

            ChannelFuture future = b.bind(CommonConstants.SERVER_PORT).sync();
            long cost = System.currentTimeMillis()-start;
            LOGGER.info("[MasterServer] Startup at port:{} cost:{}[ms]",CommonConstants.SERVER_PORT,cost);

            // 等待服务端Socket关闭
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            LOGGER.error("InterruptedException:",e);
        } finally {
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
        }
    }

    private static class MasterServerInitializer extends ChannelInitializer<SocketChannel> {

        private String zkAddress;

        MasterServerInitializer(String zkAddress){
            this.zkAddress = zkAddress;
        }

        @Override
        public void initChannel(SocketChannel ch) {
            ChannelPipeline pipeline = ch.pipeline();

            pipeline.addLast(new HttpServerCodec());
            addAdvanced(pipeline);
            pipeline.addLast(new ChunkedWriteHandler());
            pipeline.addLast(new MasterServerHandler(zkAddress));
        }

        /**
         * 可以在 HttpServerCodec 之后添加这些 ChannelHandler 进行开启高级特性
         */
        private void addAdvanced(ChannelPipeline pipeline){
            if(CommonConstants.USE_COMPRESS) {
                // 对 http 响应结果开启 gizp 压缩
                pipeline.addLast(new HttpContentCompressor());
            }
            if(CommonConstants.USE_AGGREGATOR) {
                // 将多个HttpRequest组合成一个FullHttpRequest
                pipeline.addLast(new HttpObjectAggregator(CommonConstants.MAX_CONTENT_LENGTH));
            }
        }

    }

}