-- M0 修正：resource_acl.effect 与 Java 枚举(ResourceAcl.Effect: ALLOW/DENY)大小写对齐
ALTER TABLE platform.resource_acl DROP CONSTRAINT resource_acl_effect_check;
ALTER TABLE platform.resource_acl ADD CONSTRAINT resource_acl_effect_check
  CHECK (effect IN ('ALLOW','DENY'));
