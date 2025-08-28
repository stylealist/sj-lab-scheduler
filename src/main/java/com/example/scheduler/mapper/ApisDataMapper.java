package com.example.scheduler.mapper;

import com.example.scheduler.dto.BusCityInfoDto;
import com.example.scheduler.dto.BusRouteInfoDto;
import com.example.scheduler.dto.BusStopInfoDto;
import com.example.scheduler.dto.ConvenienceStoreDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ApisDataMapper {
    int insertApisBusCityInfo(@Param("list") List<BusCityInfoDto> list);
    List<String> insertApisBusRouteInfo(@Param("list") List<BusRouteInfoDto> list);
    List<BusCityInfoDto> selectApisBusCityInfo();
    List<String> insertApisBusStopInfo(@Param("list") List<BusStopInfoDto> list);
    void insertApisBusStopInfoGeoJson();

}
