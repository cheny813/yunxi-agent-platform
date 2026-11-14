package io.yunxi.ingress;

/**
 * 入口织面。web 协议适配器（HTTP / SSE 长连接）。这是平台默认且最通用的入口。
 */
public class WebProtocolAdapter extends AbstractProtocolAdapter {

    @Override
    public String protocol() {
        return "web";
    }
}
