/**
 * ============================================================================
 * PageAgentSDK — Universal SDK Wrapper Framework
 * ============================================================================
 *
 * KEY DESIGN: LLM prompts live on the BACKEND (page-agent-config.yml).
 * Frontend only provides: pageType, pageData, tools (execute functions).
 *
 * Panel lifecycle: dispose-and-recreate pattern
 *   - SDK X button → agent.dispose() → wrapper removed from DOM
 *   - We detect disposal → show floating ball
 *   - Ball click → re-init if disposed, or just show panel
 *
 * Action aliases: "click" → "click_element_by_index", etc.
 *   Registered in instance.tools Map after PageAgent creation.
 *
 * @version 3.3.0
 */
(function () {
  'use strict';

  var LOG = '[PageAgentSDK]';

  // ─── Type annotation → Zod schema converter ─────────────────────

  function typeToZod(z, typeDef) {
    if (typeof typeDef === 'object' && typeDef !== null) {
      var shape = {};
      for (var key in typeDef) {
        if (typeDef.hasOwnProperty(key)) shape[key] = typeToZod(z, typeDef[key]);
      }
      return z.object(shape);
    }
    if (typeof typeDef !== 'string') return z.any();

    var t = typeDef.trim();
    var optional = t.endsWith('?');
    var base = optional ? t.slice(0, -1) : t;
    var schema;

    if (base.indexOf('enum:') === 0) {
      schema = z.enum(base.slice(5).split(',').map(function (s) { return s.trim(); }));
    } else {
      switch (base) {
        case 'string':  schema = z.string(); break;
        case 'number':  schema = z.number(); break;
        case 'int':     schema = z.int(); break;
        case 'boolean': schema = z.boolean(); break;
        default:        schema = z.any();
      }
    }
    return optional ? schema.optional() : schema;
  }

  // ─── Context → Instructions ──────────────────────────────────────

  function contextToInstructions(systemPrompt, pageData, toolDescriptions) {
    var lines = [systemPrompt || 'You are an AI assistant with page automation tools.'];

    if (toolDescriptions && Object.keys(toolDescriptions).length > 0) {
      lines.push('');
      lines.push('Available custom tools:');
      for (var name in toolDescriptions) {
        if (toolDescriptions.hasOwnProperty(name)) {
          lines.push('  - ' + name + ': ' + toolDescriptions[name]);
        }
      }
    }

    if (pageData && typeof pageData === 'object') {
      lines.push('');
      lines.push('Current page data:');
      flattenContext(pageData, lines, '  ');
    }

    return lines.join('\n');
  }

  function flattenContext(obj, lines, prefix, seen) {
    if (!seen) seen = new Set();
    for (var key in obj) {
      if (!obj.hasOwnProperty(key)) continue;
      var val = obj[key];
      if (val && typeof val === 'object' && !Array.isArray(val)) {
        if (seen.has(val)) {
          lines.push(prefix + key + ': [circular]');
          continue;
        }
        seen.add(val);
        lines.push(prefix + key + ':');
        flattenContext(val, lines, prefix + '  ', seen);
      } else if (Array.isArray(val)) {
        lines.push(prefix + key + ': ' + (val.length === 0 ? '(empty)' :
          typeof val[0] === 'object' ? '[' + val.length + ' items]' : val.join(', ')));
      } else {
        lines.push(prefix + key + ': ' + (val != null ? val : 'not set'));
      }
    }
  }

  // ─── SDK IIFE Loader ─────────────────────────────────────────────

  function loadSDK() {
    return new Promise(function (resolve, reject) {
      if (window.PageAgent && window.PageAgent.prototype && window.PageAgent.prototype.execute) {
        resolve();
        return;
      }
      var script = document.createElement('script');
      script.src = '/js/page-agent.sdk.js';
      script.onload = function () { resolve(); };
      script.onerror = function () { reject(new Error('page-agent.sdk.js load failed')); };
      document.head.appendChild(script);
    });
  }

  // ─── customFetch (with credentials for cookie-based auth) ────────

  function createCustomFetch(apiBase) {
    return async function (url, options) {
      return fetch(apiBase + '/v1/chat/completions', Object.assign({}, options, {
        credentials: 'include',
        headers: Object.assign({}, options && options.headers, { 'X-Agent-Token': 'page-agent-sdk' }),
      }));
    };
  }

  // ─── Fetch agent config from backend (YAML-driven) ───────────────

  async function fetchAgentConfig(apiBase, pageType) {
    try {
      var resp = await fetch(apiBase + '/v1/agent/config?pageType=' + encodeURIComponent(pageType), {
        credentials: 'include',
      });
      if (resp.ok) return await resp.json();
    } catch (e) {
      console.warn(LOG, 'Config fetch error:', e.message);
    }
    return null;
  }

  // ─── Error indicator ─────────────────────────────────────────────

  function showError(message) {
    var existing = document.getElementById('page-agent-sdk-error');
    if (existing) existing.remove();
    var el = document.createElement('div');
    el.id = 'page-agent-sdk-error';
    el.style.cssText = 'position:fixed;bottom:20px;right:20px;z-index:2147483647;background:#ef4444;color:white;' +
      'padding:12px 16px;border-radius:8px;font-size:13px;max-width:360px;box-shadow:0 4px 12px rgba(0,0,0,0.3);' +
      'cursor:pointer;font-family:system-ui,sans-serif;';
    el.textContent = 'AI助手初始化失败: ' + message;
    el.onclick = function () { el.remove(); };
    document.body.appendChild(el);
    setTimeout(function () { if (el.parentNode) el.remove(); }, 15000);
  }

  // ─── Floating Ball ───────────────────────────────────────────────
  // When panel is disposed (user clicked X), show ball to allow re-opening.

  function createBall(onClick) {
    var ball = document.createElement('div');
    ball.id = 'page-agent-sdk-ball';
    ball.innerHTML = '<svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" stroke-width="2">' +
      '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/></svg>';
    ball.title = 'AI助手 (点击展开)';

    var style = document.createElement('style');
    style.textContent =
      '#page-agent-sdk-ball{position:fixed;bottom:24px;right:24px;z-index:2147483646;width:48px;height:48px;' +
      'border-radius:50%;background:linear-gradient(135deg,#39b6ff,#bd45fb);color:white;display:flex;' +
      'align-items:center;justify-content:center;cursor:pointer;box-shadow:0 4px 16px rgba(57,182,255,0.4),' +
      '0 2px 8px rgba(0,0,0,0.3);transition:transform .2s,box-shadow .2s;user-select:none;-webkit-user-select:none}' +
      '#page-agent-sdk-ball:hover{transform:scale(1.12);box-shadow:0 6px 24px rgba(57,182,255,0.6),' +
      '0 4px 12px rgba(0,0,0,0.4)}' +
      '#page-agent-sdk-ball:active{transform:scale(0.95)}' +
      '#page-agent-sdk-ball.dragging{opacity:0.8;cursor:grabbing}' +
      '@keyframes ballPulse{0%,100%{box-shadow:0 4px 16px rgba(57,182,255,0.4),0 2px 8px rgba(0,0,0,0.3)}' +
      '50%{box-shadow:0 4px 24px rgba(57,182,255,0.7),0 2px 12px rgba(0,0,0,0.4)}}' +
      '#page-agent-sdk-ball.pulse{animation:ballPulse 2s ease-in-out infinite}';
    document.head.appendChild(style);

    ball.classList.add('pulse');
    ball.addEventListener('click', function (e) {
      if (ball._wasDragged) { ball._wasDragged = false; return; }
      onClick();
    });

    makeDraggable(ball, true);
    document.body.appendChild(ball);
    return ball;
  }

  function showBall(ball) { if (ball) ball.style.display = 'flex'; }
  function hideBall(ball) { if (ball) ball.style.display = 'none'; }

  // ─── Draggable helper ────────────────────────────────────────────

  function makeDraggable(el, isBall) {
    var startX, startY, startLeft, startTop, moved, rafId;

    el.addEventListener('mousedown', onDown);
    el.addEventListener('touchstart', onDown, { passive: false });

    function onDown(e) {
      moved = false;
      var evt = e.touches ? e.touches[0] : e;
      startX = evt.clientX;
      startY = evt.clientY;
      var rect = el.getBoundingClientRect();
      startLeft = rect.left;
      startTop = rect.top;

      el.classList.add('dragging');
      document.addEventListener('mousemove', onMove);
      document.addEventListener('mouseup', onUp);
      document.addEventListener('touchmove', onMove, { passive: false });
      document.addEventListener('touchend', onUp);
      if (e.touches) e.preventDefault();
    }

    function onMove(e) {
      var evt = e.touches ? e.touches[0] : e;
      var dx = evt.clientX - startX;
      var dy = evt.clientY - startY;
      if (Math.abs(dx) > 3 || Math.abs(dy) > 3) moved = true;

      if (rafId) cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(function () {
        var newLeft = Math.max(0, Math.min(window.innerWidth - el.offsetWidth, startLeft + dx));
        var newTop = Math.max(0, Math.min(window.innerHeight - el.offsetHeight, startTop + dy));

        el.style.left = newLeft + 'px';
        el.style.top = newTop + 'px';
        el.style.right = 'auto';
        el.style.bottom = 'auto';
      });
      if (e.touches) e.preventDefault();
    }

    function onUp() {
      if (rafId) { cancelAnimationFrame(rafId); rafId = null; }
      el.classList.remove('dragging');
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
      document.removeEventListener('touchmove', onMove);
      document.removeEventListener('touchend', onUp);
      if (isBall && moved) el._wasDragged = true;
    }
  }

  // ─── Action Aliases ──────────────────────────────────────────────
  // Register common LLM shorthand names that map to real built-in tools.
  // This makes the agent tolerant of models that use "click" instead of
  // "click_element_by_index", etc.

  function addAliases(instance) {
    try {
      var toolsMap = instance.tools;
      if (!toolsMap || typeof toolsMap.get !== 'function') return;
      var aliases = {
        'click': 'click_element_by_index',
        'type': 'input_text',
        'select': 'select_dropdown_option',
      };
      var added = [];
      for (var alias in aliases) {
        if (aliases.hasOwnProperty(alias) && !toolsMap.has(alias) && toolsMap.has(aliases[alias])) {
          toolsMap.set(alias, toolsMap.get(aliases[alias]));
          added.push(alias + '→' + aliases[alias]);
        }
      }
      if (added.length > 0) {
        console.log(LOG, 'Action aliases registered: ' + added.join(', '));
      }
    } catch (e) {
      console.warn(LOG, 'Action alias setup failed (tools Map may be minified):', e);
    }
  }

  // ─── Custom Panel Styles ─────────────────────────────────────────

  function injectPanelStyles() {
    if (document.getElementById('page-agent-sdk-styles')) return;
    var style = document.createElement('style');
    style.id = 'page-agent-sdk-styles';
    style.textContent =
      // CRITICAL: Disable SDK's transition:all 0.3s which makes dragging laggy
      '#page-agent-runtime_agent-panel{transition:none!important}' +
      // Override SDK's transform:translateX(-50%) centering (we position via left directly)
      '#page-agent-runtime_agent-panel{transform:none!important}' +
      // Rounded header
      '#page-agent-runtime_agent-panel [class*="header"]{border-radius:16px!important}' +
      // Better close button hover
      '#page-agent-runtime_agent-panel [class*="stopButton"]{transition:background .2s!important}' +
      '#page-agent-runtime_agent-panel [class*="stopButton"]:hover{background:rgba(239,68,68,0.4)!important}';
    document.head.appendChild(style);
  }

  // ─── Check if agent instance is still alive ──────────────────────

  function isAgentAlive(instance) {
    if (!instance) return false;
    try {
      var wrapper = instance.panel && instance.panel.wrapper;
      return !!(wrapper && wrapper.parentNode);
    } catch (e) {
      return false;
    }
  }

  // ─── PageAgentSDK public API ─────────────────────────────────────

  var SDK = {
    _instance: null,
    _ball: null,
    _initConfig: null,

    /**
     * Initialize the AI assistant.
     *
     * Minimal usage:
     *   PageAgentSDK.init({
     *     pageType: 'recipe-make',
     *     pageData: { schoolId: '123' },
     *     tools: { recipe_balance: async (input) => { ... } },
     *   })
     *
     * Advanced usage:
     *   PageAgentSDK.init({
     *     pageType: 'recipe-make',
     *     pageData: { ... },
     *     tools: { ... },
      *     apiBase: '', // API基础地址，使用环境变量配置
     *     model: 'qwen-plus',
     *     language: 'zh-CN',
     *     enableMask: false,
     *     promptForNextTask: true,
     *     onAfterTask: (result) => { ... },
     *   })
     */
    init: async function (config) {
      config = config || {};

      // Double-init protection: if agent is still alive, just show panel
      if (isAgentAlive(this._instance)) {
        console.log(LOG, 'Agent already running, showing panel');
        this._instance.panel.show();
        hideBall(this._ball);
        return this._instance;
      }

       var apiBase = config.apiBase || '';

      // Store config for re-initialization after disposal
      this._initConfig = config;

      // 1. Load SDK IIFE
      try {
        await loadSDK();
      } catch (e) {
        showError('SDK加载失败 (' + e.message + ')');
        return this._fallbackLegacy(apiBase, config);
      }

      var z = window.PageAgentZod;
      var toolFn = window.PageAgentTool;
      var PageAgentClass = window.PageAgent;

      if (!PageAgentClass) {
        showError('PageAgent类未找到');
        return this._fallbackLegacy(apiBase, config);
      }

      // 2. Fetch agent config from backend
      var backendConfig = null;
      if (config.pageType) {
        backendConfig = await fetchAgentConfig(apiBase, config.pageType);
      }

      // 3. Build custom tools
      var customTools = {};
      var toolDescriptions = {};
      var toolDefs = (backendConfig && backendConfig.tools) || {};
      var frontendTools = config.tools || {};

      // Merge: add frontend-only tools not already defined in backend YAML
      for (var fname in frontendTools) {
        if (frontendTools.hasOwnProperty(fname) && !toolDefs[fname]) {
          var ft = frontendTools[fname];
          toolDefs[fname] = (typeof ft === 'function' || (ft && !ft.description && !ft.params))
            ? { description: fname, params: {} }
            : ft;
        }
      }

      if (z && toolFn) {
        for (var name in toolDefs) {
          if (!toolDefs.hasOwnProperty(name)) continue;
          var def = toolDefs[name];
          try {
            var inputSchema = def.params ? typeToZod(z, def.params) : z.object({});
            var executeFn = (frontendTools[name] && typeof frontendTools[name] === 'function')
              ? frontendTools[name]
              : (frontendTools[name] && frontendTools[name].execute)
                ? frontendTools[name].execute
                : (def.execute ? def.execute : async function () { return 'no execute'; });
            customTools[name] = toolFn({
              description: def.description || name,
              inputSchema: inputSchema,
              execute: executeFn,
            });
            toolDescriptions[name] = def.description || name;
          } catch (err) {
            console.error(LOG, 'Tool "' + name + '" build failed:', err);
          }
        }
      }

      // 4. Build instructions (from backend YAML systemPrompt + pageData)
      var instructions = config.instructions;
      if (!instructions) {
        instructions = {
          system: contextToInstructions(
            (backendConfig && backendConfig.systemPrompt) || '',
            config.pageData,
            toolDescriptions
          ),
        };
      }

      // 5. Create PageAgent instance
      var agentConfig = {
        model: (backendConfig && backendConfig.model) || config.model || 'qwen-plus',
        baseURL: apiBase + '/v1',
        apiKey: '',
        language: config.language || 'zh-CN',
        maxSteps: config.maxSteps || 1000,
        customFetch: createCustomFetch(apiBase),
        enableMask: config.enableMask || false,
        promptForNextTask: config.promptForNextTask !== undefined ? config.promptForNextTask : true,
        instructions: instructions,
        customTools: Object.keys(customTools).length > 0 ? customTools : undefined,
        onAfterTask: config.onAfterTask,
      };

      try {
        this._instance = new PageAgentClass(agentConfig);
      } catch (e) {
        showError('PageAgent创建失败: ' + (e.message || e));
        return null;
      }

      // 6. Add action aliases for common LLM shorthand names
      addAliases(this._instance);

      // 7. Inject custom panel styles
      injectPanelStyles();

      // 8. Position panel at bottom-right and make draggable
      try {
        var wrapper = this._instance.panel.wrapper;
        if (wrapper) {
          // Set initial position: bottom-right corner via inline style
          var panelW = wrapper.offsetWidth || 360;
          wrapper.style.left = (window.innerWidth - panelW - 24) + 'px';
          wrapper.style.right = 'auto';
          wrapper.style.bottom = '24px';
          wrapper.style.top = 'auto';
          // Note: transform:none is set via injectPanelStyles() CSS !important
          // to override SDK's translateX(-50%) centering

          // Wrap panel.show() to re-apply our positioning after SDK resets it
          var panel = this._instance.panel;
          var origShow = panel.show.bind(panel);
          var savedLeft = wrapper.style.left;
          var savedTop = wrapper.style.top;
          panel.show = function () {
            origShow();
            // SDK's show() resets transform and opacity — CSS !important handles transform,
            // but we need to restore position if it was dragged
            var currentLeft = wrapper.style.left;
            if (currentLeft && currentLeft !== '50%') {
              // User has dragged — keep their position
              wrapper.style.right = 'auto';
            }
          };

          var header = wrapper.querySelector('[class*="header"]');
          if (header) {
            makeDraggable(wrapper, false);
            header.style.cursor = 'grab';
          }
        }
      } catch (e) {
        console.warn(LOG, 'Draggable setup failed:', e);
      }

      // 9. Create floating ball (hidden initially)
      var self = this;
      if (!this._ball) {
        this._ball = createBall(function () {
          self.show();
        });
      }
      hideBall(this._ball);

      // 10. Listen for agent disposal → show ball
      //     When user clicks X, SDK calls agent.dispose() which dispatches 'dispose' event.
      //     Panel's own listener removes wrapper from DOM. We show ball so user can re-open.
      this._instance.addEventListener('dispose', function () {
        console.log(LOG, 'Agent disposed, showing floating ball');
        // Small delay to let Panel's own dispose handler complete
        setTimeout(function () {
          showBall(self._ball);
        }, 200);
      });

      // 11. Show Panel
      try {
        this._instance.panel.show();
      } catch (e) {
        showError('Panel显示失败: ' + (e.message || e));
      }

      console.log(LOG, 'Initialized: pageType=' + config.pageType +
        ', tools=' + Object.keys(customTools).join(','));
      return this._instance;
    },

    // ─── Public properties ─────────────────────────────────────────

    get z() { return window.PageAgentZod; },
    get tool() { return window.PageAgentTool; },
    get instance() { return this._instance; },

    // ─── Public control methods ────────────────────────────────────

    /**
     * Execute a task programmatically.
     * @param {string} task - Task description
     * @returns {Promise<ExecutionResult>}
     */
    execute: function (task) {
      if (!this._instance) return Promise.reject(new Error('Not initialized'));
      return this._instance.execute(task);
    },

    /**
     * Show the panel. If agent was disposed, re-initialize automatically.
     */
    show: function () {
      if (isAgentAlive(this._instance)) {
        // Agent still alive — just show panel
        this._instance.panel.show();
        hideBall(this._ball);
        return;
      }
      // Agent disposed — re-initialize
      if (this._initConfig) {
        console.log(LOG, 'Agent disposed, re-initializing...');
        var self = this;
        this.init(this._initConfig).then(function () {
          hideBall(self._ball);
        });
      }
    },

    /**
     * Hide the panel and show the floating ball.
     */
    hide: function () {
      if (isAgentAlive(this._instance)) {
        this._instance.panel.hide();
      }
      showBall(this._ball);
    },

    /**
     * Full cleanup: dispose agent and remove ball.
     */
    dispose: function () {
      if (this._instance) {
        try { this._instance.dispose(); } catch (_) {}
        this._instance = null;
      }
      if (this._ball) {
        this._ball.remove();
        this._ball = null;
      }
      this._initConfig = null;
    },

    // ─── Legacy fallback ───────────────────────────────────────────

    _fallbackLegacy: function (apiBase) {
      if (window.PageAgent && typeof window.PageAgent.init === 'function') {
        window.PageAgent.init({ apiBase: apiBase + '/api/page-agent' });
      }
    },
  };

  window.PageAgentSDK = SDK;
})();
