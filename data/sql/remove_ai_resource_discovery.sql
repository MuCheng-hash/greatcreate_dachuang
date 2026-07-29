USE red_culture_platform;

-- Permanently remove the retired AI/AMap nearby resource discovery storage.
DROP TABLE IF EXISTS resource_discovery_run_item;
DROP TABLE IF EXISTS resource_discovery_candidate;
DROP TABLE IF EXISTS resource_discovery_run;
