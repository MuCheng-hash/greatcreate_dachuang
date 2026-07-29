package com.redculture.platform.service;

import com.redculture.platform.vo.RedCultureSiteDetailVO;
import com.redculture.platform.vo.RedCultureSiteMarkerVO;

import java.util.List;

public interface RedCultureGraphMapService {
    List<RedCultureSiteMarkerVO> listPublishedSites(String district);
    RedCultureSiteDetailVO getPublishedSite(String siteId);
}
