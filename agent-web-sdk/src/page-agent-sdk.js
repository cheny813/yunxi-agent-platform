/**
 * ============================================================================
 * PageAgent SDK Loader — 按需加载 page-agent.sdk.js，暴露增强 pageController
 * ============================================================================
 *
 * 归属：yunxi-agent-platform/agent-web-sdk（单一源，勿在产品中拷贝）
 *
 * 本模块不再包含 LLM 决策循环（Panel / Ball / init / show / hide 均已移除）。
 * 仅负责：
 *   1. 加载 /js/page-agent.sdk.js（208KB 原生 SDK）
 *   2. 创建一次性 pageController，供 PageAgentDomEngine.scan() 增强元素检测
 *
 * PageAgentDomEngine 的工作方式：
 *   - 扫描时先检查 window.__PAGE_AGENT__.pageController.getBrowserState()
 *   - 若 SDK 已加载并注入 → 使用 SDK 的交互元素检测（更全）
 *   - 若未加载 → 使用内置 DOM 扫描器（零依赖，始终可用）
 *
 * 注入位置：window.__PAGE_AGENT__（pageController 引用）
 *
 * 注意：加载 SDK 是可选的。不加载时 DomEngine 仍可正常工作，
 *      仅交互元素检测精度可能略低于 SDK（如 Shadow DOM 穿透等）。
 */

(function () {
  'use strict';

  var LOG = '[PageAgentSdkLoader]';
  var _loading = false;
  var _loaded = false;
  var _sdkUrl = '/js/page-agent.sdk.js';

  /**
   * 加载原生 page-agent SDK 脚本。
   * @returns {Promise<Object>} resolve 为 pageController（SDK 原生交互元素 API）
   */
  function loadSdk(apiBase) {
    if (_loaded && window.__PAGE_AGENT__) {
      return Promise.resolve(window.__PAGE_AGENT__);
    }
    if (_loading) {
      return new Promise(function (resolve) {
        var check = setInterval(function () {
          if (_loaded && window.__PAGE_AGENT__) {
            clearInterval(check);
            resolve(window.__PAGE_AGENT__);
          }
        }, 100);
      });
    }

    _loading = true;
    apiBase = apiBase || window.location.origin;

    return new Promise(function (resolve, reject) {
      // 检查是否已由其他方式加载
      if (window.PageAgent && window.__PAGE_AGENT__) {
        _loaded = true;
        _loading = false;
        resolve(window.__PAGE_AGENT__);
        return;
      }

      var script = document.createElement('script');
      script.src = apiBase.replace(/\/+$/, '') + _sdkUrl + '?autoInit=false';
      script.onload = function () {
        if (!window.PageAgent) {
          _loading = false;
          reject(new Error('SDK 加载后未暴露 window.PageAgent'));
          return;
        }

        // 创建最小化 PageAgent 实例（仅取 pageController，不启动 LLM 循环）
        // autoInit=false 使 SDK 不自动创建 Panel/Ball
        try {
          var agent = new window.PageAgent({
            // 最小化配置：不启动 Agent 循环，仅用于 DOM 扫描
            autoStart: false,
            enableUI: false,
          });

          var ctrl = agent.pageController ||
                     (agent.panel && agent.panel.pageController) ||
                     null;

          if (ctrl && typeof ctrl.getBrowserState === 'function') {
            window.__PAGE_AGENT__ = { pageController: ctrl, agent: agent };
            _loaded = true;
            _loading = false;
            console.log(LOG, 'SDK 已加载，pageController 已注入 DomEngine');
            resolve(window.__PAGE_AGENT__);
          } else {
            _loading = false;
            reject(new Error('SDK 实例未暴露 pageController'));
          }
        } catch (e) {
          _loading = false;
          reject(e);
        }
      };

      script.onerror = function () {
        _loading = false;
        reject(new Error('SDK 脚本加载失败: ' + _sdkUrl));
      };

      document.head.appendChild(script);
    });
  }

  /** 检查 SDK 是否已加载并可用。 */
  function isLoaded() {
    return _loaded && !!window.__PAGE_AGENT__;
  }

  /**
   * 获取当前已注入的 pageController（需先调用 loadSdk）。
   * @returns {Object|null} pageController 或 null
   */
  function getPageController() {
    return (window.__PAGE_AGENT__ && window.__PAGE_AGENT__.pageController) || null;
  }

  /** 设置 SDK URL（默认 /js/page-agent.sdk.js）。 */
  function setSdkUrl(url) {
    _sdkUrl = url;
  }

  // ── 公开 API ────────────────────────────────────────────────────────────
  window.PageAgentSDK = {
    loadSdk: loadSdk,
    isLoaded: isLoaded,
    getPageController: getPageController,
    setSdkUrl: setSdkUrl,
  };

})();
