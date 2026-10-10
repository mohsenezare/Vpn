package libbox

import (
 "context"
 "crypto/tls"
 "fmt"
 "net"
 "net/http"
 "time"
 "github.com/sagernet/sing-box/adapter"
 "github.com/sagernet/sing/common/ntp"
 M "github.com/sagernet/sing/common/metadata"
)

// MeasureOutbound never uses a direct fallback. Time includes proxy handshake,
// destination TLS and the complete HTTPS response headers.
func (s *BoxService) MeasureOutbound(tag string, timeoutMillis int64) (int64, error) {
 outbound, ok := s.instance.Outbound().Outbound(tag)
 if !ok { return 0, fmt.Errorf("unknown outbound") }
 if timeoutMillis < 100 || timeoutMillis > 15000 { return 0, fmt.Errorf("invalid timeout") }
 ctx, cancel := context.WithTimeout(s.ctx, time.Duration(timeoutMillis)*time.Millisecond)
 defer cancel()
 transport := &http.Transport{
  DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
   return outbound.DialContext(ctx, network, M.ParseSocksaddr(address))
  },
  TLSClientConfig: &tls.Config{RootCAs: adapter.RootPoolFromContext(s.ctx), Time: ntp.TimeFuncFromContext(s.ctx)},
  DisableKeepAlives: true,
 }
 defer transport.CloseIdleConnections()
 client := &http.Client{Transport:transport, Timeout:time.Duration(timeoutMillis)*time.Millisecond,
  CheckRedirect:func(*http.Request, []*http.Request)error{return http.ErrUseLastResponse}}
 req, err := http.NewRequestWithContext(ctx, http.MethodHead, "https://www.gstatic.com/generate_204", nil)
 if err != nil { return 0, err }
 start := time.Now()
 response, err := client.Do(req)
 if err != nil { return 0, err }
 response.Body.Close()
 if response.StatusCode != http.StatusNoContent { return 0, fmt.Errorf("unexpected HTTP status %d",response.StatusCode) }
 latency := time.Since(start).Milliseconds()
 if latency < 1 { latency = 1 }
 return latency, nil
}
