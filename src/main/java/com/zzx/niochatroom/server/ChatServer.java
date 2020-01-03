package com.zzx.niochatroom.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.util.Set;

public class ChatServer {

    private static final int DEFAULT_PORT = 8888;
    private static final String QUIT = "quit";
    private static final int BUFFER = 1024;

    // 处理服务器IO的通道
    private ServerSocketChannel server;
    private Selector selector;
    // 读缓冲区
    private ByteBuffer rBuffer = ByteBuffer.allocate(BUFFER);
    // 写缓冲区
    private ByteBuffer wBuffer = ByteBuffer.allocate(BUFFER);
    // 编码格式
    private Charset charset = Charset.forName("UTF-8");
    private int port;

    public ChatServer() {
        this(DEFAULT_PORT);
    }

    public ChatServer(int port) {
        this.port = port;
    }

    private void start() {
        try {
            server = ServerSocketChannel.open();
            // 和BIO是非常相似的
            // 为了设置NIO非阻塞的模型设计，一定要把模式改为false
            server.configureBlocking(false);
            server.socket().bind(new InetSocketAddress(port));

            // 注册到Selector
            selector = Selector.open();
            server.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("启动服务器， 监听端口：" + port + "...");

            // 因为不止有一个selector调用个，所以用while循环
            while (true) {
                // selector可以注册多个channel
                // 如果selector上没有事件发生，是不会返回的
                // 如果selector发生了事件，会返回一个整数（有多少个监听的事件被触发了）
                selector.select();
                Set<SelectionKey> selectionKeys = selector.selectedKeys();
                for (SelectionKey key : selectionKeys) {
                    // 处理被触发的事件
                    handles(key);
                }
                // 清空处理过的select的key
                // 如果没有处理过key，那么下次添加key的时候，可能旧的selector又处理了一次
                selectionKeys.clear();
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            // 关闭selector
            // 注册解除
            // 通道解除
            close(selector);
        }

    }

    private void handles(SelectionKey key) throws IOException {
        // ACCEPT事件 - 和客户端建立了连接
        // 是否为Accept事件
        if (key.isAcceptable()) {
            // 获取通道
            ServerSocketChannel server = (ServerSocketChannel) key.channel();
            SocketChannel client = server.accept();
            // 配置非阻塞
            client.configureBlocking(false);
            // 注册selector
            client.register(selector, SelectionKey.OP_READ);
            System.out.println(getClientName(client) + "已连接");
        }
        // READ事件 - 客户端发送了消息
        // 是否为Read事件
        else if (key.isReadable()) {
            SocketChannel client = (SocketChannel) key.channel();
            String fwdMsg = receive(client);
            if (fwdMsg.isEmpty()) {
                // 客户端异常
                // 注销key的通道
                key.cancel();
                // 操作立刻返回
                selector.wakeup();
            } else {
                System.out.println(getClientName(client) + ":" + fwdMsg);
                forwardMessage(client, fwdMsg);

                // 检查用户是否退出
                if (readyToQuit(fwdMsg)) {
                    key.cancel();
                    selector.wakeup();
                    System.out.println(getClientName(client) + "已断开");
                }
            }

        }
    }

    private void forwardMessage(SocketChannel client, String fwdMsg) throws IOException {
        for (SelectionKey key : selector.keys()) {
            // 遍历key对应的channel
            Channel connectedClient = key.channel();
            // 如果key属于ServerSocketChannel就跳过去
            if (connectedClient instanceof ServerSocketChannel) {
                continue;
            }

            if (key.isValid() && !client.equals(connectedClient)) {
                wBuffer.clear();
                wBuffer.put(charset.encode(getClientName(client) + ":" + fwdMsg));
                // 写状态反转成读状态
                wBuffer.flip();
                while (wBuffer.hasRemaining()) {
                    ((SocketChannel) connectedClient).write(wBuffer);
                }
            }
        }
    }

    private String receive(SocketChannel client) throws IOException {
        // 清空buffer消息防止消息的污染
        rBuffer.clear();
        // 如果buffer里有消息就一直读
        while (client.read(rBuffer) > 0) ;
        // 由读模式转换成写模式
        rBuffer.flip();
        return String.valueOf(charset.decode(rBuffer));
    }

    private String getClientName(SocketChannel client) {
        return "客户端[" + client.socket().getPort() + "]";
    }

    private boolean readyToQuit(String msg) {
        return QUIT.equals(msg);
    }

    private void close(Closeable closable) {
        if (closable != null) {
            try {
                closable.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        ChatServer chatServer = new ChatServer(7777);
        chatServer.start();
    }
}
