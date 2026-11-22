package com.pulse.gateway;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.assertj.core.api.Assertions.assertThat;

/** Rate-limit keys: one IPv4 address, or one IPv6 /64 (what an ISP hands a single customer). */
class ClientIpResolverTest {

    @Test
    void ipv4IsKeyedByTheFullAddress() throws Exception {
        assertThat(ClientIpResolver.rateLimitKey(InetAddress.getByName("203.0.113.7"))).isEqualTo("203.0.113.7");
    }

    @Test
    void addressesInTheSameIpv6Slash64ShareOneBucket() throws Exception {
        String a = ClientIpResolver.rateLimitKey(InetAddress.getByName("2001:db8:1:2::1"));
        String b = ClientIpResolver.rateLimitKey(InetAddress.getByName("2001:db8:1:2:ffff:ffff:ffff:fffe"));
        String otherCustomer = ClientIpResolver.rateLimitKey(InetAddress.getByName("2001:db8:1:3::1"));

        assertThat(a).isEqualTo(b).isEqualTo("20010db800010002::/64");
        assertThat(otherCustomer).isNotEqualTo(a);
    }
}
