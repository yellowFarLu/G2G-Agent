package com.wikiagent.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * v1-v2 §5 用户档案 Spring Data JPA Repository。
 * <p>
 * 复用 JpaRepository 内置方法（findById / save / deleteById 等），无需自定义查询。
 * 装配 Bean 名默认为 userProfileJpaDao（接口名首字母小写）。
 */
public interface UserProfileJpaDao extends JpaRepository<UserProfileEntity, String> {
}
