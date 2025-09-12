package com.example.scheduler.mapper;

import com.example.scheduler.dto.ConvenienceStoreDto;
import com.example.scheduler.dto.GovernmentOfficeInfoDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface SafemapMapper {
    int insertConvenienceStore(@Param("list") List<ConvenienceStoreDto> list);
    void insertConvenienceStoreGeojson();
    int insertgovernmentOffice(@Param("list") List<GovernmentOfficeInfoDto> list);
    void insertgovernmentOfficeGeojson();

}
