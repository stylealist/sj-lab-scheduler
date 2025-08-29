package com.example.scheduler.mapper;

import com.example.scheduler.dto.BusCityInfoDto;
import com.example.scheduler.dto.BusRouteInfoDto;
import com.example.scheduler.dto.CctvInfoDto;
import com.example.scheduler.service.ItsDataService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface ItsDataMapper {
    int countCctvByKey(@Param("type") Short type,
                       @Param("format") String format,
                       @Param("name") String name);

    int updateCctvUrlByKey(@Param("type") Short type,
                           @Param("format") String format,
                           @Param("name") String name,
                           @Param("url") String url);

    int insertCctvInfoOne(@Param("dto") CctvInfoDto dto);

}
