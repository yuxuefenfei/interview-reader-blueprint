import {createRouter, createWebHashHistory} from "vue-router";
import DashboardView from "../views/DashboardView.vue";
import UploadReleaseView from "../views/UploadReleaseView.vue";
import ReleaseListView from "../views/ReleaseListView.vue";
import OperationListView from "../views/OperationListView.vue";
import OperationDetailView from "../views/OperationDetailView.vue";

export default createRouter({
    history: createWebHashHistory(),
    routes: [
        {path: "/", name: "dashboard", component: DashboardView},
        {path: "/upload", name: "upload", component: UploadReleaseView},
        {path: "/releases", name: "releases", component: ReleaseListView},
        {path: "/operations", name: "operations", component: OperationListView},
        {path: "/operations/:id", name: "operation-detail", component: OperationDetailView},
        {path: "/:pathMatch(.*)*", redirect: "/"}
    ]
});