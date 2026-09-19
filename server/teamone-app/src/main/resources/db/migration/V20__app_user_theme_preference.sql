-- V20：R-11 个人设置真实化（B6 批 · docs/v2/12 §1 R-11）
-- theme_preference 落 platform.app_user（全栈评审必改④：独立迁移，老用户 NULL 不报错）。
-- 取值约定：light / dark / system（NULL 视同 system 由前端解析）；登录/刷新后经
-- GET /api/v1/auth/me（UserView.themePreference）下发，PUT /api/v1/me 持久化。
ALTER TABLE platform.app_user ADD COLUMN theme_preference text;

COMMENT ON COLUMN platform.app_user.theme_preference IS '界面主题偏好（R-11）：light/dark/system，NULL 视同 system';
