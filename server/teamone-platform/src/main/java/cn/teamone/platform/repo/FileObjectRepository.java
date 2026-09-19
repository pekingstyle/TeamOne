package cn.teamone.platform.repo;

import cn.teamone.platform.domain.FileObject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** 文件元数据仓库（platform.file，V9；消息附件 ready 校验/下载取原名的取数口）。 */
public interface FileObjectRepository extends JpaRepository<FileObject, UUID> {

    /** 消息附件引用校验（INC-2 红线 4：attachments.fileId 必须全部 ready） */
    List<FileObject> findAllByIdIn(List<UUID> ids);
}
