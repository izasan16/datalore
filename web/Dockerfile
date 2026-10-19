# Build stage
FROM docker.m.daocloud.io/library/node:20-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci
COPY . .
RUN npm run build

# Run stage: nginx serving dist + reverse proxy to server
FROM docker.m.daocloud.io/library/nginx:alpine
COPY --from=build /app/dist /usr/share/nginx/html/datalore
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
