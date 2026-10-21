# 反代到 Spring Boot（由 nginx.conf / nginx-tls.conf include）
set $backend "http://app:8080";
proxy_pass $backend;
proxy_http_version 1.1;
proxy_set_header Host $host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header Authorization $http_authorization;
proxy_set_header Connection "";
proxy_buffering off;
proxy_cache off;
proxy_connect_timeout 10s;
proxy_send_timeout 360s;
proxy_read_timeout 360s;
