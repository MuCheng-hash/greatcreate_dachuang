export interface AmapClientConfig {
  amapKey?: string;
  amapSecurityJsCode?: string;
}

type AmapApi = Record<string, any>;
type AmapWindow = Window & typeof globalThis & {
  AMap?: AmapApi;
  _AMapSecurityConfig?: { securityJsCode: string };
  [key: string]: any;
};

let loadPromise: Promise<AmapApi> | null = null;

export function loadAmap(config: AmapClientConfig | null): Promise<AmapApi> {
  const target = window as AmapWindow;
  if (target.AMap) return Promise.resolve(target.AMap);
  if (loadPromise) return loadPromise;
  if (!config?.amapKey) return Promise.reject(new Error("地图密钥未配置"));

  target._AMapSecurityConfig = { securityJsCode: config.amapSecurityJsCode || "" };
  loadPromise = new Promise<AmapApi>((resolve, reject) => {
    const callbackName = `__amapReady${Date.now()}`;
    const script = document.createElement("script");
    target[callbackName] = () => {
      delete target[callbackName];
      if (target.AMap) resolve(target.AMap);
      else reject(new Error("地图服务未正确初始化"));
    };
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(config.amapKey || "")}&callback=${callbackName}`;
    script.onerror = () => {
      loadPromise = null;
      reject(new Error("地图服务加载失败"));
    };
    document.head.appendChild(script);
  });
  return loadPromise;
}
