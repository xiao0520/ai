package org.ruoyi.common.core.service;

import jakarta.servlet.http.HttpServletResponse;
import org.ruoyi.common.core.domain.dto.OssDTO;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 通用 OSS服务
 *
 * @author xiaoqh
 * @date 2026-08-20
 */
public interface OssService {

    /**
     * 通过ossId查询对应的url
     *
     * @param ossIds ossId串逗号分隔
     * @return url串逗号分隔
     */
    String selectUrlByIds(String ossIds);

    /**
     * 通过ossId查询列表
     *
     * @param ossIds ossId串逗号分隔
     * @return 列表
     */
    List<OssDTO> selectByIds(String ossIds);

    /**
     * 上传 MultipartFile 到对象存储服务
     */
    OssDTO uploadFile(MultipartFile file);

    /**
     * 使用指定 OSS 配置上传文件。
     *
     * @param file 文件
     * @param configKey sys_oss_config.config_key
     * @return OSS 文件信息
     */
    OssDTO uploadFile(MultipartFile file, String configKey);

    /**
     * 使用默认 OSS 配置上传业务文件，并将文件存入默认前缀下的业务子目录。
     *
     * @param file 文件
     * @param businessPath 业务子目录，例如 provider-icon
     * @return OSS 文件信息
     */
    OssDTO uploadBusinessFile(MultipartFile file, String businessPath);

    /**
     * Upload a server-side file without buffering the complete payload in memory.
     */
    OssDTO uploadFile(File file);

    /**
     * Stream an object as an authenticated attachment response.
     */
    void downloadFile(Long ossId, HttpServletResponse response) throws IOException;

    /**
     * Delete one object and its sys_oss metadata.
     */
    Boolean deleteFile(Long ossId);

}
