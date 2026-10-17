// ============================================================================
//  k6 商品详情压测脚本（多级缓存场景）
//
//  关键不是 QPS，而是响应体里的 source 字段分布：
//  L1 命中应该占绝大多数，DB 应该接近 0。
// ============================================================================
import http from 'k6/http';
import { check } from 'k6';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const PRODUCT = __ENV.PRODUCT_ID || '1001';

export const options = {
  scenarios: {
    browse: {
      executor: 'constant-vus',
      vus: Number(__ENV.VUS || 400),
      duration: __ENV.DURATION || '30s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<50'],
    http_req_failed: ['rate<0.01'],
  },
};

export default function () {
  const res = http.get(`${BASE}/api/product/detail/${PRODUCT}`, {
    tags: { name: 'product-detail' },
  });
  check(res, {
    'HTTP 200': (r) => r.status === 200,
    'has data source': (r) => {
      try {
        const body = r.json();
        return body && body.data && body.data.source !== undefined;
      } catch (e) {
        return false;
      }
    },
  });
}
