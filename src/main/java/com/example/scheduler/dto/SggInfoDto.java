package com.example.scheduler.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 버스 도시 정보 DTO (map.bus_city_info)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SggInfoDto implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 결과코드 (예: 00) */
    private String sggCd;

    /** 결과메시지 (예: OK) */
    private String sggNm;

    /** 도시코드 (예: 22) - PK */
    private String geom;

    /** 도시명 (예: 대구광역시) */
    private String regDate;
}



