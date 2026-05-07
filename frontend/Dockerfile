FROM node:22-alpine AS build

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
ARG VITE_API_BASE_URL=http://localhost:7070
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

FROM nginx:1.27-alpine

ENV TRADINGBOT_API_BASE_URL=http://localhost:7070

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY docker-entrypoint.d/40-tradingbot-env.sh /docker-entrypoint.d/40-tradingbot-env.sh
RUN chmod +x /docker-entrypoint.d/40-tradingbot-env.sh
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 8080
