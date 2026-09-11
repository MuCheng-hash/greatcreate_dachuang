package com.redculture.platform.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;

/**
 * 注册 MySQL 数据源事务管理器。
 */
@Configuration
@MapperScan("com.redculture.platform.mapper")
public class MybatisPlusConfig {

    /**
     * 创建 MySQL 数据源事务管理器。
     *
     * @param dataSource dataSource 参数
     * @return MySQL 事务管理器
     */
    @Bean(name = "mysqlTransactionManager")
    public DataSourceTransactionManager mysqlTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
