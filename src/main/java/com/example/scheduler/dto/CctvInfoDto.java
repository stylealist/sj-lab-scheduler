package com.example.scheduler.dto;

import lombok.*;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CctvInfoDto {
    private Long id;

    private String coordType;      // coordtype
    private Integer dataCount;     // datacount
    private String roadSectionId;  // roadsectionid
    private String fileCreateTime; // filecreatetime (원본 문자열 YYYYMMDDHH24MISS)
    private Short cctvType;        // cctvtype (1~5)
    private String cctvUrl;        // cctvurl
    private String cctvResolution; // cctvresolution
    private Double lonDeg;         // coordx (경도, WGS84)
    private Double latDeg;         // coordy (위도, WGS84)
    private String cctvFormat;     // cctvformat
    private String cctvName;       // cctvname

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
