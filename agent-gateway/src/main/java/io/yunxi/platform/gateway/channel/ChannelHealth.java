package io.yunxi.platform.gateway.channel;

import io.yunxi.platform.gateway.model.PlatformType;

/**
 * 通道健康状态
 */
public class ChannelHealth {

    private final PlatformType platform;
    private final boolean connected;
    private final String status;
    private final String error;

    private ChannelHealth(PlatformType platform, boolean connected, String status, String error) {
        this.platform = platform;
        this.connected = connected;
        this.status = status;
        this.error = error;
    }

    public static ChannelHealth healthy(PlatformType platform) {
        return new ChannelHealth(platform, true, "CONNECTED", null);
    }

    public static ChannelHealth unhealthy(PlatformType platform, String error) {
        return new ChannelHealth(platform, false, "DISCONNECTED", error);
    }

    public static ChannelHealth connecting(PlatformType platform) {
        return new ChannelHealth(platform, false, "CONNECTING", null);
    }

    public PlatformType getPlatform() { return platform; }
    public boolean isConnected() { return connected; }
    public String getStatus() { return status; }
    public String getError() { return error; }
}
