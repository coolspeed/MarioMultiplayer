using NetMQ.Sockets;
using System;


namespace ConsoleApp1
{
    public class Program
    {
        public static void Main(string[] args)
        {
            using (var xsubSocket = new XSubscriberSocket("@tcp://*:1234"))
            using (var xpubSocket = new XPublisherSocket("@tcp://*:5678"))
            {
                Console.WriteLine("Intermediary started, and waiting for messages");

                // proxy messages between frontend / backend
                var proxy = new NetMQ.Proxy(xsubSocket, xpubSocket);

                // blocks indefinitely
                proxy.Start();
            }
        }
    }
}
