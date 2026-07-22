import { createApp } from "vue";
import { ElAlert } from "element-plus/es/components/alert/index";
import { ElButton, ElButtonGroup } from "element-plus/es/components/button/index";
import { ElCard } from "element-plus/es/components/card/index";
import { ElCollapse, ElCollapseItem } from "element-plus/es/components/collapse/index";
import { provideGlobalConfig } from "element-plus/es/components/config-provider/index";
import { ElDrawer } from "element-plus/es/components/drawer/index";
import { ElDialog } from "element-plus/es/components/dialog/index";
import { ElDropdown, ElDropdownItem, ElDropdownMenu } from "element-plus/es/components/dropdown/index";
import { ElEmpty } from "element-plus/es/components/empty/index";
import { ElForm, ElFormItem } from "element-plus/es/components/form/index";
import { ElIcon } from "element-plus/es/components/icon/index";
import { ElInput } from "element-plus/es/components/input/index";
import { ElLoading } from "element-plus/es/components/loading/index";
import { ElOption, ElSelect } from "element-plus/es/components/select/index";
import { ElProgress } from "element-plus/es/components/progress/index";
import { ElRadioButton, ElRadioGroup } from "element-plus/es/components/radio/index";
import { ElTable, ElTableColumn } from "element-plus/es/components/table/index";
import { ElTag } from "element-plus/es/components/tag/index";
import { ElTooltip } from "element-plus/es/components/tooltip/index";
import { ElTree } from "element-plus/es/components/tree/index";
import { ElUpload } from "element-plus/es/components/upload/index";
import zhCn from "element-plus/es/locale/lang/zh-cn";
import "element-plus/dist/index.css";
import App from "./App.vue";
import { registerServiceWorker } from "./offline/serviceWorkerRegistration";
import router from "./router";
import "./styles.css";

const app = createApp(App).use(router);

[
  ElAlert,
  ElButton,
  ElButtonGroup,
  ElCard,
  ElCollapse,
  ElCollapseItem,
  ElDrawer,
  ElDialog,
  ElDropdown,
  ElDropdownItem,
  ElDropdownMenu,
  ElEmpty,
  ElForm,
  ElFormItem,
  ElIcon,
  ElInput,
  ElOption,
  ElProgress,
  ElRadioButton,
  ElRadioGroup,
  ElSelect,
  ElTable,
  ElTableColumn,
  ElTag,
  ElTooltip,
  ElTree,
  ElUpload,
].forEach((component) => app.use(component));
app.use(ElLoading);
provideGlobalConfig({ locale: zhCn }, app, true);
app.mount("#app");
registerServiceWorker();
