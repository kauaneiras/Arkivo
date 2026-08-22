# ============================================
# Lab DMS Frontend — React + Vite + Nginx
# ============================================
FROM node:20-alpine AS builder

WORKDIR /app
COPY package*.json ./
# npm install (not ci) tolerates a lockfile that lags behind package.json
RUN npm install

COPY . .

# Vite reads env vars at BUILD time. Default is a relative URL,
# proxied by nginx below to the backend container.
ARG VITE_API_URL=/api/v1
ENV VITE_API_URL=${VITE_API_URL}

RUN npm run build

# ============================================
# Production server — Nginx (SPA + API proxy)
# ============================================
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html

RUN printf 'server {\n\
    listen 3000;\n\
    client_max_body_size 20M;\n\
\n\
    # API proxy -> backend container (same-origin, no CORS)\n\
    location /api/ {\n\
        proxy_pass http://backend:8080/api/;\n\
        proxy_set_header Host $host;\n\
        proxy_set_header X-Real-IP $remote_addr;\n\
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;\n\
        proxy_set_header X-Forwarded-Proto $scheme;\n\
        proxy_read_timeout 120s;\n\
    }\n\
\n\
    # React Router SPA fallback\n\
    location / {\n\
        root /usr/share/nginx/html;\n\
        index index.html;\n\
        try_files $uri $uri/ /index.html;\n\
    }\n\
}\n' > /etc/nginx/conf.d/default.conf

EXPOSE 3000
CMD ["nginx", "-g", "daemon off;"]
