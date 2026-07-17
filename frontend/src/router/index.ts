import { createRouter, createWebHistory } from "vue-router";
import { ADMIN_MOBILE_MEDIA_QUERY } from "../shared/responsive";

const ReaderView = () => import("../views/ReaderView.vue");
const AdminLayout = () => import("../layouts/AdminLayout.vue");
const AdminDocumentsView = () => import("../views/AdminDocumentsView.vue");
const AdminDocumentDetailView = () => import("../views/AdminDocumentDetailView.vue");
const ImportCenterView = () => import("../views/ImportCenterView.vue");
const VersionEditorView = () => import("../views/VersionEditorView.vue");
const DetachedPreviewView = () => import("../views/DetachedPreviewView.vue");

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/reader" },
    { path: "/reader", component: ReaderView },
    { path: "/reader/documents/:documentId", component: ReaderView },
    { path: "/admin/versions/:versionId/preview", component: DetachedPreviewView },
    { path: "/admin", component: AdminLayout, children: [
      { path: "", redirect: "/admin/documents" },
      { path: "documents", component: AdminDocumentsView },
      { path: "documents/:documentId", component: AdminDocumentDetailView },
      { path: "imports", component: ImportCenterView },
      { path: "versions/:versionId/edit", component: VersionEditorView }
    ] },
    { path: "/:pathMatch(.*)*", redirect: "/reader" }
  ]
});

const adminViewport = window.matchMedia(ADMIN_MOBILE_MEDIA_QUERY);
const isAdminPath = (path: string): boolean => path === "/admin" || path.startsWith("/admin/");

router.beforeEach((to) => {
  if (isAdminPath(to.path) && adminViewport.matches) return "/reader";
  return true;
});

adminViewport.addEventListener("change", (event) => {
  if (event.matches && isAdminPath(router.currentRoute.value.path)) void router.replace("/reader");
});

export default router;
