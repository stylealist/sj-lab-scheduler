package com.example.scheduler.mapper;

import com.example.scheduler.dto.ConvenienceStoreDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SafemapMapper {
    int insertConvenienceStore(@Param("list") List<ConvenienceStoreDto> list);
}
