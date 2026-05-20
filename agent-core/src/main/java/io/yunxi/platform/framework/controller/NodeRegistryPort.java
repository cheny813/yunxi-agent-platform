package io.yunxi.platform.framework.controller;

/**
 * 鑺傜偣注册同步绔彛
 *
 * <p>
 * agent-core锛圵ebSocket 浼犺緭灞傦級通过姝ゆ帴鍙ｅ皢鑺傜偣注册/鏂繛/蹇冭烦
 * 同步通知鍒?agent-business锛圖evOps API 灞傦級锛屾浛浠ｅ師鍏堢殑事件椹卞姩鏂瑰紡銆?
 * </p>
 *
 * <h3>瑙ｅ喅鐨勯棶棰?/h3>
 * <ul>
 *   <li>DesktopRelayHandler 鍜?NodeRegistryService 涓ゅ鐙珛注册琛ㄧ殑数据涓€鑷存€ч棶棰?/li>
 *   <li>事件椹卞姩异步同步鐨勬椂搴忛棿闅欓棶棰橈紙注册事件可能鍦ㄦ煡璇箣鍚庢墠处理锛?/li>
 * </ul>
 *
 * <h3>璁捐鍘熷垯</h3>
 * <ul>
 *   <li>同步调用锛氭敞鍐?注销/蹇冭烦鍦?WebSocket 处理线程鍐呭悓姝ュ畬鎴愶紝鏃犲欢杩?/li>
 *   <li>鍙€変緷璧栵細{@code @Autowired(required = false)}锛宎gent-business 鏈姞杞芥椂自动璺宠繃</li>
 *   <li>接口闅旂锛歛gent-core 涓嶄緷璧?agent-business 鐨勫叿浣撳疄鐜扮被</li>
 * </ul>
 *
 */
public interface NodeRegistryPort {

    /**
     * 鑺傜偣注册通知
     *
     * @param info      鑺傜偣信息锛堟潵鑷?WebSocket 注册消息锛?
     * @param sessionId WebSocket 浼氳瘽 ID
     */
    void onNodeRegistered(NodeInfo info, String sessionId);

    /**
     * 鑺傜偣鏂繛通知
     *
     * @param clientId 鏂繛鐨勫鎴风 ID
     */
    void onNodeDisconnected(String clientId);

    /**
     * 鑺傜偣蹇冭烦通知
     *
     * @param clientId 蹇冭烦鐨勫鎴风 ID
     */
    void onNodeHeartbeat(String clientId);
}
