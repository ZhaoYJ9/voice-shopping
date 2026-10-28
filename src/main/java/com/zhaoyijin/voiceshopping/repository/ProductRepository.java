package com.zhaoyijin.voiceshopping.repository;

import com.zhaoyijin.voiceshopping.entity.ProductEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<ProductEntity, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM ProductEntity p WHERE p.id = :id")
    Optional<ProductEntity> findByIdForUpdate(@Param("id") Long id);

    List<ProductEntity> findByStatus(String status);

    List<ProductEntity> findByIdIn(List<Long> ids);

    @Query("SELECT p FROM ProductEntity p WHERE p.id IN :ids AND " +
            "(:merchantIds IS NULL OR p.merchantId IN :merchantIds)")
    List<ProductEntity> findByIdInWithScope(@Param("ids") List<Long> ids,
                                            @Param("merchantIds") List<Long> merchantIds);

}
