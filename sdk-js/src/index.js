/**
 * yunxi Agent Platform SDK 入口文件
 * @version 2.1.0
 */

const AgentClient = require('./AgentClient');
const DesktopClient = require('./DesktopClient');

module.exports = {
  AgentClient,
  DesktopClient
};

// ES Module exports
module.exports.AgentClient = AgentClient;
module.exports.DesktopClient = DesktopClient;