package com.example.scheduler.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BusRouteInfoDto {
    private String routeId;          // route_id
    private String routeNo;          // route_no
    private String routeTp;          // route_tp
    private String startNodeNm;      // start_node_nm
    private String endNodeNm;        // end_node_nm
    private String startVehicleTime; // start_vehicle_time (HHMM)
    private String endVehicleTime;   // end_vehicle_time   (HHMM)
    private String cityCode;
    private String cityName;
}
