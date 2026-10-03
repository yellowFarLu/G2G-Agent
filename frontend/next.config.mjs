/** @type {import('next').NextConfig} */
const isStatic = process.env.NEXT_BUILD_STATIC === 'true';

const nextConfig = {
  output: isStatic ? 'export' : 'standalone',
  // 旧一级路由统一 307 到单页控制台（/?tab=xxx）。
  // dev/standalone 模式下 Next.js 本身处理；export 产物托管到 Spring Boot 时由
  // LegacyRedirectController 做同样的 307 重定向，保证旧书签/硬链接不 404。
  //（output: export 不支持 redirects，故仅在非静态模式注册）
  ...(isStatic
    ? { images: { unoptimized: true } }
    : {
        async redirects() {
          return [
            { source: '/chat', destination: '/?tab=chat', permanent: false },
            { source: '/upload', destination: '/?tab=upload', permanent: false },
            { source: '/tasks', destination: '/?tab=tasks', permanent: false },
            { source: '/tasks/:taskId', destination: '/?tab=tasks&taskId=:taskId', permanent: false },
            { source: '/workbench', destination: '/?tab=workbench', permanent: false },
            { source: '/graph', destination: '/?tab=graph', permanent: false },
            { source: '/settings', destination: '/?tab=settings', permanent: false },
            { source: '/results/:docId', destination: '/?tab=upload&docId=:docId', permanent: false },
          ];
        },
      }),
  // rewrites 仅用于 dev/standalone 模式做 /api 代理；export 模式由 Spring Boot 同域提供。
  ...(isStatic
    ? {}
    : {
        async rewrites() {
          const apiBase = process.env.API_BASE_URL || 'http://localhost:8080';
          return [
            {
              source: '/api/:path*',
              destination: `${apiBase}/api/:path*`,
            },
          ];
        },
      }),
};

export default nextConfig;
