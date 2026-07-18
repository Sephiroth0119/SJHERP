package com.sjherp.app.config;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.sjherp.domain.catalog.InventoryCategory;
import com.sjherp.domain.catalog.Product;
import com.sjherp.domain.catalog.ProductRepository;

/** 隔离集成测试的商品分类夹具；临时商品 ID 一律按库存商品映射到 1405。 */
@Configuration(proxyBeanMethods = false)
public class ProductRepositoryTestConfig {

    @Bean
    ProductRepository productRepository() {
        Product product = mock(Product.class);
        when(product.getInventoryCategory()).thenReturn(InventoryCategory.MERCHANDISE);

        ProductRepository repository = mock(ProductRepository.class);
        when(repository.findById(anyLong())).thenReturn(Optional.of(product));
        return repository;
    }
}
