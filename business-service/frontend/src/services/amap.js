let loadPromise;

export function loadAmap(config) {
  if (window.AMap) return Promise.resolve(window.AMap);
  if (loadPromise) return loadPromise;
  if (!config?.amapKey) return Promise.reject(new Error("地图密钥未配置"));

  window._AMapSecurityConfig = { securityJsCode: config.amapSecurityJsCode || "" };
  loadPromise = new Promise((resolve, reject) => {
    const callbackName = `__amapReady${Date.now()}`;
    const script = document.createElement("script");
    window[callbackName] = () => {
      delete window[callbackName];
      resolve(window.AMap);
    };
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(config.amapKey)}&callback=${callbackName}`;
    script.onerror = () => reject(new Error("地图服务加载失败"));
    document.head.appendChild(script);
  });
  return loadPromise;
}
