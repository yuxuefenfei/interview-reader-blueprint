import { createRouter, createWebHistory } from "vue-router";
import ReaderView from "../views/ReaderView.vue";
import AdminLayout from "../layouts/AdminLayout.vue";
import AdminDocumentsView from "../views/AdminDocumentsView.vue";
import ImportCenterView from "../views/ImportCenterView.vue";
import VersionEditorView from "../views/VersionEditorView.vue";

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/reader" },
    { path: "/reader", component: ReaderView },
    { path: "/reader/documents/:documentId", component: ReaderView },
    { path: "/admin", component: AdminLayout, children: [
      { path: "", redirect: "/admin/documents" },
      { path: "documents", component: AdminDocumentsView },
      { path: "imports", component: ImportCenterView },
      { path: "versions/:versionId/edit", component: VersionEditorView }
    ] },
    { path: "/:pathMatch(.*)*", redirect: "/reader" }
  ]
});

router.beforeEach((to) => {
  if (to.path.startsWith("/admin") && window.matchMedia("(max-width: 760px)").matches) {
    return "/reader";
  }
  return true;
});

export default router;