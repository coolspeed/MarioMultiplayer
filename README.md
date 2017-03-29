Mario Multiplayer
=================

Supermario multiplayer game, written in Java ([Processing](https://processing.org/) Java Mode) using [ZeroMQ](http://zeromq.org/) ([JeroMQ](https://github.com/zeromq/jeromq)).

It include a server which is written in C# using ZeroMQ ([NetMQ](https://github.com/zeromq/netmq)).

It's netcode is based on [State Synchronization](http://gafferongames.com/networked-physics/state-synchronization/).

The client has 1500 lines of Java code in total, and the server of 22 lines of C# code.

This project is developped as a team joining homework of [What! Studio](https://github.com/what-studio).

## Game rule

The mario who first steps over the other wins.

