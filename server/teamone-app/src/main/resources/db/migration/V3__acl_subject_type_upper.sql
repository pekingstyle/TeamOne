-- M0 修正：resource_acl.subject_type 与 Java 枚举(SubjectType: USER/ROLE/DEPARTMENT)对齐
ALTER TABLE platform.resource_acl DROP CONSTRAINT resource_acl_subject_type_check;
ALTER TABLE platform.resource_acl ADD CONSTRAINT resource_acl_subject_type_check
  CHECK (subject_type IN ('USER','ROLE','DEPARTMENT'));
