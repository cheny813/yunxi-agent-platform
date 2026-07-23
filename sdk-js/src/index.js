/**
 * yunxi Agent Platform SDK 入口文件
 * @version 2.1.0
 */

const AgentClient = require('./AgentClient');
const DesktopClient = require('./DesktopClient');
const AgentBrowserSDK = require('./AgentBrowserSDK');

module.exports = {
  AgentClient,
  DesktopClient,
  AgentBrowserSDK
};

// ES Module exports
module.exports.AgentClient = AgentClient;
module.exports.DesktopClient = DesktopClient;
module.exports.AgentBrowserSDK = AgentBrowserSDK;