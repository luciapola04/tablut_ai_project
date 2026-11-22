package it.unibo.ai.tablut.network;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Matches StreamUtils.java in AGalassi/TablutCompetition:
 * - writeString: out.writeInt(bytes.length); out.write(bytes); out.flush();
 * - readString: int len = in.readInt(); byte[] bytes = new byte[len]; in.readFully(bytes);
 */
public class NetworkClient implements AutoCloseable {
    private final Socket socket;
    private final DataOutputStream out;
    private final DataInputStream in;

    public NetworkClient(String serverIp, int port) throws IOException {
        this.socket = new Socket(serverIp, port);
        this.socket.setTcpNoDelay(true);
        this.out = new DataOutputStream(socket.getOutputStream());
        this.in = new DataInputStream(socket.getInputStream());
    }

    public void sendString(String msg) throws IOException {
        byte[] bytes = msg.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    public String readString() throws IOException {
        int length = in.readInt();
        if (length <= 0) return null;
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
