
package com.github.bigtoast

/**
 * Testing distributed code is hard. Writing tests is inherently difficult and testing 
 * for specific types of network failure can be very difficult. Mocking, in my opinion,
 * is not a valid solution as you are not testing against reality. The purpose of this 
 * library is to proxy network connections and provide a programmable api to control 
 * flow through the proxies. 
 *
 * Initially we want to start a proxy that will listen on a port, when a connection
 * to that port is made a connection handler will recieve the connection and open 
 * a connection to the target server. 
 *
 * There are two types of streams that should be available through the api. We should
 * be able to access a single connection proxy which should not affect other connection
 * proxies. We should also be able to access a stream representing all traffic from our
 * source proxy to the target socket.
 *
 * Initially lets go after just the second type. We can inturrpt all traffic from the source
 * to the target.
 */
package object rockprox 