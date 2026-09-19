package cn.teamone.prd.repo;

import cn.teamone.prd.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** 产品仓库（表 prd.product）。 */
public interface ProductRepository extends JpaRepository<Product, UUID> {

    Optional<Product> findByKey(String key);
}
