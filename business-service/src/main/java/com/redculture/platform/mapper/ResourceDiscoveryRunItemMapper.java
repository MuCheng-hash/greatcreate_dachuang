package com.redculture.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.redculture.platform.entity.ResourceDiscoveryRunItem;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResourceDiscoveryRunItemMapper extends BaseMapper<ResourceDiscoveryRunItem> {

    @Insert("INSERT INTO resource_discovery_run_item(run_id, candidate_id, result_rank, distance_meters) "
            + "VALUES(#{runId}, #{candidateId}, #{resultRank}, #{distanceMeters})")
    int insertItem(ResourceDiscoveryRunItem item);
}
