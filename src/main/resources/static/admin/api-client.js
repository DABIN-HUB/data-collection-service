(function initCollectorApi(window) {
  "use strict";

  /**
   * 解析后端上下文路径。
   *
   * @return {string} 上下文路径
   */
  function resolveContextPath() {
    const marker = "/admin/";
    const pathname = window.location.pathname;
    const index = pathname.indexOf(marker);
    if (index <= 0) {
      return "";
    }
    return pathname.substring(0, index);
  }

  /**
   * 调用后端接口。
   *
   * @param {string} path 接口路径
   * @param {object} options fetch 选项
   * @param {object} context 页面令牌上下文
   * @return {Promise<object>} 后端响应体
   */
  async function callApi(path, options = {}, context = {}) {
    const headers = new Headers(options.headers || {});
    if (!headers.has("Content-Type") && options.body) {
      headers.set("Content-Type", "application/json");
    }
    const token = typeof context.getToken === "function" ? context.getToken() : "";
    if (token) {
      headers.set("X-Collector-Token", token);
    }

    const response = await fetch(`${resolveContextPath()}${path}`, { ...options, headers });
    const body = await parseBody(response);
    if (response.status === 401 && typeof context.onUnauthorized === "function") {
      context.onUnauthorized();
    }
    validateResponse(response, body);
    return body;
  }

  /**
   * 解析响应体。
   *
   * @param {Response} response fetch 响应
   * @return {Promise<object>} 响应对象
   */
  async function parseBody(response) {
    const text = await response.text();
    if (!text) {
      return {};
    }
    try {
      return JSON.parse(text);
    } catch (error) {
      return { message: text.trim() || `HTTP ${response.status}` };
    }
  }

  /**
   * 校验后端响应。
   *
   * @param {Response} response fetch 响应
   * @param {object} body 响应体
   */
  function validateResponse(response, body) {
    if (!response.ok) {
      throw apiError(body.message || `HTTP ${response.status}`, body, response.status);
    }
    if (body.status === "error") {
      throw apiError(body.message || "请求失败", body, response.status);
    }
    if (typeof body.code === "number" && body.code !== 200) {
      throw apiError(body.message || `业务错误码 ${body.code}`, body, response.status);
    }
  }

  /**
   * 构建接口错误对象。
   *
   * @param {string} message 错误信息
   * @param {object} body 响应体
   * @param {number} httpStatus HTTP 状态码
   * @return {Error} 接口错误
   */
  function apiError(message, body, httpStatus) {
    const error = new Error(message);
    error.body = body;
    error.httpStatus = httpStatus;
    return error;
  }

  /**
   * 解包后端 data 字段。
   *
   * @param {object} body 响应体
   * @return {*} 业务数据
   */
  function dataOf(body) {
    return body && Object.prototype.hasOwnProperty.call(body, "data") ? body.data : body;
  }

  window.CollectorApi = Object.freeze({
    resolveContextPath,
    callApi,
    apiError,
    dataOf
  });
})(window);
