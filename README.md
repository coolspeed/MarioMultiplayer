Mario Multiplayer
=================

![Game Intro GIF](https://coolspeed.files.wordpress.com/2017/04/enemycd.gif?w=630)

Supermario multiplayer game, written in Java ([Processing](https://processing.org/) Java Mode) using [ZeroMQ](http://zeromq.org/) ([JeroMQ](https://github.com/zeromq/jeromq)).

This repo also includes a server which is written in C# using ZeroMQ ([NetMQ](https://github.com/zeromq/netmq)).

It's netcode is based on [State Synchronization](http://gafferongames.com/networked-physics/state-synchronization/).

The client has [1500 lines of Java code in total](https://github.com/coolspeed/MarioMultiplayer/blob/master/src/MarioMultiplay.java), and the server [22 lines of C# code](https://github.com/coolspeed/MarioMultiplayer/blob/master/server/Program.cs).

This project is developped as a team joining homework of [What! Studio](https://github.com/what-studio).

## Game rule

The mario who first steps over the other wins.

## Design philosophy

Described in my blog (in Korean):

https://coolspeed.wordpress.com/2017/04/11/supermario_multiplayer_postmortem/
