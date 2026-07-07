import { createApp } from "vue";
import App from "./App.vue";
import { registerServiceWorker } from "./offline/serviceWorkerRegistration";
import "./styles.css";

createApp(App).mount("#app");
registerServiceWorker();
