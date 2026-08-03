import { createApp } from "vue";
import { provideGlobalConfig } from "element-plus/es/components/config-provider/index";
import { ElLoading } from "element-plus/es/components/loading/index";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/es/components/loading/style/css";
import "element-plus/es/components/message/style/css";
import "element-plus/es/components/message-box/style/css";
import App from "./App.vue";
import { registerServiceWorker } from "./offline/serviceWorkerRegistration";
import router from "./router";
import "./styles.css";

const app = createApp(App).use(router);
app.use(ElLoading);
provideGlobalConfig({ locale: zhCn }, app, true);
app.mount("#app");
registerServiceWorker();
