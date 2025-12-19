package com.example.scheduler.service.impl;

import com.example.scheduler.dto.*;
import com.example.scheduler.mapper.ApisDataMapper;
import com.example.scheduler.mapper.CommonMapper;
import com.example.scheduler.service.ApisDataService;
import com.example.scheduler.service.CommonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.example.scheduler.util.DataTypeUtil.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommonServiceImpl implements CommonService {
    private final CommonMapper mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Override
    public List<SggInfoDto> selectSggList() throws Exception {
        return mapper.selectSggList();
    }
}
