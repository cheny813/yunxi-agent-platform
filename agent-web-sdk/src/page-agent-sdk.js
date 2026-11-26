/**
 * ============================================================================
 * PageAgent SDK Loader — 按需加载 page-agent.sdk.js，暴露增强 pageController
 * ============================================================================
 *
 * 归属：yunxi-agent-platform/agent-web-sdk（单一源，勿在产品中拷贝）
 *
 * 本模块不包含 LLM 决策循环，仅负责：
 *   1. 按候选顺序加载 page-agent.sdk.js（v1.12+ IIFE 构建产物）
 *   2. 用 SDK 暴露的 PageAgentPageController 类实例化 pageController，
 *      供 PageAgentDomEngine.scan() 增强元素检测（Shadow DOM 穿透等）
 *
 * 加载候选（依次尝试，前者失败自动回退）：
 *   1. setSdkUrl() 显式指定的 URL
 *   2. 同源后端透传 /js/page-agent.sdk.js
 *   3. 本包内置产物 dist/page-agent.sdk.js（vite 经 import.meta.url 解析，
 *      dev 与 build 均可用）
 *
 * 注入位置：window.__PAGE_AGENT__（pageController 引用）
 *
 * 注意：加载 SDK 是可选的。全部候选失败时 DomEngine 仍可用内置 DOM 扫描器
 *      正常工作，仅交互元素检测精度略低。
 */

(function () {
  'use strict';

  var LOG = '[PageAgentSdkLoader]';
  var _loading = false;
  var _loaded = false;
  var _sdkUrl = null; // 显式覆盖时设置；null 时按默认候选顺序加载

  /** 构建候选 URL 列表（按优先级）。 */
  function candidateUrls() {
    var urls = [];
    if (_sdkUrl) {
      urls.push(_sdkUrl);
    } else {
      urls.push('/js/page-agent.sdk.js'); // 后端透传（存在时优先，走同源）
      urls.push(new URL('../dist/page-agent.sdk.js', import.meta.url).href);
    }
    return urls;
  }

  /** 注入单个 script，resolve 表示加载成功（window.PageAgentPageController 就绪）。 */
  function injectScript(url) {
    return new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.src = url;
      script.onload = function () {
        if (window.PageAgentPageController) {
          resolve();
        } else {
          reject(new Error('SDK 加载后未暴露 window.PageAgentPageController: ' + url));
        }
      };
      script.onerror = function () {
        reject(new Error('SDK 脚本加载失败: ' + url));
      };
      document.head.appendChild(script);
    });
  }

  /**
   * 加载 SDK 并实例化 pageController（PageController 独立实例，无 Panel UI、
   * 无 Agent 循环）。
   * @returns {Promise<Object>} resolve 为 window.__PAGE_AGENT__（含 pageController）
   */
  function loadSdk() {
    if (_loaded && window.__PAGE_AGENT__) {
      return Promise.resolve(window.__PAGE_AGENT__);
    }
    if (_loading) {
      return new Promise(function (resolve, reject) {
        var check = setInterval(function () {
          if (_loaded && window.__PAGE_AGENT__) {
            clearInterval(check);
            resolve(window.__PAGE_AGENT__);
          }
        }, 100);
        // 防御：全部候选失败时不再无限轮询
        setTimeout(function () {
          if (!_loaded) {
            clearInterval(check);
            reject(new Error('SDK 加载超时'));
          }
        }, 15000);
      });
    }

    _loading = true;

    var urls = candidateUrls();
    var attempt = function (i) {
      if (i >= urls.length) {
        _loading = false;
        return Promise.reject(new Error('SDK 全部候选加载失败: ' + urls.join(', ')));
      }
      return injectScript(urls[i]).then(function () {
        try {
          var ctrl = new window.PageAgentPageController();
          window.__PAGE_AGENT__ = { pageController: ctrl };
          _loaded = true;
          _loading = false;
          console.log(LOG, 'SDK 已加载，pageController 已注入 DomEngine（' + urls[i] + '）');
          return window.__PAGE_AGENT__;
        } catch (e) {
          _loading = false;
          reject(e);
        }
      }, function () {
        return attempt(i + 1);
      });
    };

    return attempt(0);
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

  /** 设置 SDK URL（设置后跳过默认候选顺序，仅加载该 URL）。 */
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
