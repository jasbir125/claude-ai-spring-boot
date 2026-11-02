package com.example.order.repository;

import com.example.order.model.Order;
import com.example.order.model.OrderStatus;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Lines are loaded lazily in batches ({@code hibernate.default_batch_fetch_size}), not one query per order. */
public interface OrderRepository extends JpaRepository<Order, Long> {

    Page<Order> findByUserId(Long userId, Pageable pageable);

    Page<Order> findByStatus(OrderStatus status, Pageable pageable);

    Page<Order> findByUserIdAndStatus(Long userId, OrderStatus status, Pageable pageable);

    List<Order> findByUserIdAndStatusOrderById(Long userId, OrderStatus status);
}
