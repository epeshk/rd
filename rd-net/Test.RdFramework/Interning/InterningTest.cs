﻿using System;
using System.Collections.Generic;
using System.Linq;
using JetBrains.Diagnostics;
using JetBrains.Rd;
using JetBrains.Rd.Base;
using JetBrains.Rd.Impl;
using NUnit.Framework;
using Test.RdFramework.Components;

namespace Test.RdFramework.Interning
{
  [TestFixture]
  [Apartment(System.Threading.ApartmentState.STA)]
  public class InterningTest : RdFrameworkTestBase
  {
    [SetUp]
    public void BeforeMethod()
    {
      ServerWire.AutoTransmitMode = true;
      ClientWire.AutoTransmitMode = true;
    }
    
    [Test]
    public void TestClientToServer() { DoTest(true, true); }
    
    [Test]
    public void TestServerToClient() { DoTest(false, false); }
    
    [Test]
    public void TestClientThenServerMixed() { DoTest(true, false); }
    
    [Test]
    public void TestServerThenClientMixed() { DoTest(false, true); }
    
    [Test]
    public void TestClientThenServerMixedAndReversed() { DoTest(true, false, true); }
    
    [Test]
    public void TestServerThenClientMixedAndReversed() { DoTest(false, true, true); }
    
    private long MeasureBytes(IProtocol protocol, Action action)
    {
      var pre = ((TestWire) protocol.Wire).BytesSent;
      action();
      return ((TestWire) protocol.Wire).BytesSent - pre;
    }

    private void DoTest(bool firstClient, bool secondClient, bool thenSwitchSides = false)
    {
      var serverProperty =
        new RdProperty<InterningTestModel>(InterningTestModel.Read, InterningTestModel.Write) {IsMaster = true}
          .Static(1);
      var clientProperty =
        new RdProperty<InterningTestModel>(InterningTestModel.Read, InterningTestModel.Write) {IsMaster = false}
          .Static(1);
      var serverPropertyWrapper = new InterningTestPropertyWrapper<InterningTestModel>(serverProperty, ServerProtocol.SerializationContext);
      var clientPropertyWrapper = new InterningTestPropertyWrapper<InterningTestModel>(clientProperty, ClientProtocol.SerializationContext);

      serverPropertyWrapper.mySerializationContext =
        ServerProtocol.SerializationContext.WithInternRootsHere(serverPropertyWrapper, "Test");
      clientPropertyWrapper.mySerializationContext =
        ClientProtocol.SerializationContext.WithInternRootsHere(clientPropertyWrapper, "Test");
      
      
      serverPropertyWrapper.Bind(LifetimeDefinition.Lifetime, ServerProtocol, "top");
      clientPropertyWrapper.Bind(LifetimeDefinition.Lifetime, ClientProtocol, "top");

      var serverModel = new InterningTestModel("");

      serverProperty.Value = serverModel;
      var clientModel = clientProperty.Value;

      var simpleTestData = new List<(int, string)> {(0, ""), (1, "test"), (2, "why")};

      var firstSenderProtocol = firstClient ? ClientProtocol : ServerProtocol;
      var firstSenderModel = firstClient ? clientModel : serverModel;

      var firstBytesWritten = MeasureBytes(firstSenderProtocol, () =>
      {
        foreach (var pair in simpleTestData)
        {
          firstSenderModel.Issues[pair.Item1] = new WrappedStringModel(pair.Item2);
        }
      });

      var secondSenderProtocol = secondClient ? ClientProtocol : ServerProtocol;
      var secondSenderModel = secondClient ? clientModel : serverModel;

      var secondBytesWritten = MeasureBytes(secondSenderProtocol, () =>
      {
        foreach (var pair in simpleTestData)
        {
          secondSenderModel.Issues[pair.Item1 + simpleTestData.Count] = new WrappedStringModel(pair.Item2);
        }
      });

      Assertion.Assert(firstBytesWritten - simpleTestData.Sum(it => it.Item2.Length) >= secondBytesWritten,
        "Interning must save bytes");

      var firstReceiver = firstClient ? serverModel : clientModel;
      var secondReceiver = secondClient ? serverModel : clientModel;

      foreach (var pair in simpleTestData)
      {
        Assertion.Assert(pair.Item2 == firstReceiver.Issues[pair.Item1].Text, "Data must match");
        Assertion.Assert(pair.Item2 == secondReceiver.Issues[pair.Item1 + simpleTestData.Count].Text,
          "Data must match");
      }

      if (!thenSwitchSides) return;

      var extraString = "again";

      var thirdBytesWritten = MeasureBytes(secondSenderProtocol, () =>
      {
        foreach (var pair in simpleTestData)
        {
          secondSenderModel.Issues[pair.Item1 + simpleTestData.Count * 2] =
            new WrappedStringModel(pair.Item2 + extraString);
        }
      });

      var fourthBytesWritten = MeasureBytes(firstSenderProtocol, () =>
      {
        foreach (var pair in simpleTestData)
        {
          firstSenderModel.Issues[pair.Item1 + simpleTestData.Count * 3] =
            new WrappedStringModel(pair.Item2 + extraString);
        }
      });

      Assertion.Assert(thirdBytesWritten - simpleTestData.Sum(it => it.Item2.Length + extraString.Length) >=
                       fourthBytesWritten, "Interning must save bytes");

      foreach (var pair in simpleTestData)
      {
        Assertion.Assert(pair.Item2 + extraString == secondReceiver.Issues[pair.Item1 + simpleTestData.Count * 2].Text,
          "Data must match");
        Assertion.Assert(pair.Item2 + extraString == firstReceiver.Issues[pair.Item1 + simpleTestData.Count * 3].Text,
          "Data must match");
      }
    }
    
    private static int SumLengths(InterningNestedTestModel value)
    {
      return value.Value.Length * 2 + 4 + (value.Inner == null ? 0 : SumLengths(value.Inner));
    }

    [Test]
    public void TestNestedInterning()
    {
      ServerProtocol.Serializers.Register(InterningNestedTestModel.Read, InterningNestedTestModel.Write);
      ClientProtocol.Serializers.Register(InterningNestedTestModel.Read, InterningNestedTestModel.Write);
      
      var serverProperty =
        new RdProperty<InterningNestedTestModel>(InterningNestedTestModel.Read.Interned("Test"), InterningNestedTestModel.Write.Interned("Test")) {IsMaster = true}
          .Static(1);
      var clientProperty =
        new RdProperty<InterningNestedTestModel>(InterningNestedTestModel.Read.Interned("Test"), InterningNestedTestModel.Write.Interned("Test")) {IsMaster = false}
          .Static(1);
      var serverPropertyWrapper = new InterningTestPropertyWrapper<InterningNestedTestModel>(serverProperty, ServerProtocol.SerializationContext);
      var clientPropertyWrapper = new InterningTestPropertyWrapper<InterningNestedTestModel>(clientProperty, ClientProtocol.SerializationContext);

      serverPropertyWrapper.mySerializationContext =
        ServerProtocol.SerializationContext.WithInternRootsHere(serverPropertyWrapper, "Test");
      clientPropertyWrapper.mySerializationContext =
        ClientProtocol.SerializationContext.WithInternRootsHere(clientPropertyWrapper, "Test");
      
      
      serverPropertyWrapper.Bind(LifetimeDefinition.Lifetime, ServerProtocol, "top");
      clientPropertyWrapper.Bind(LifetimeDefinition.Lifetime, ClientProtocol, "top");

      var testValue = new InterningNestedTestModel("extremelyLongString",
        new InterningNestedTestModel("middle", new InterningNestedTestModel("bottom", null)));

      var firstSendBytes = MeasureBytes(ServerProtocol, () =>
      {
        serverProperty.Value = testValue;
        Assertion.Assert(Equals(testValue, clientProperty.Value), "Received value should be the same as sent one");
      });

      var secondSendBytes = MeasureBytes(ServerProtocol, () =>
      {
        serverProperty.Value = testValue.Inner;
        Assertion.Assert(Equals(testValue.Inner, clientProperty.Value),
          "Received value should be the same as sent one");
      });

      var thirdSendBytes = MeasureBytes(ServerProtocol, () =>
      {
        serverProperty.Value = testValue;
        Assertion.Assert(Equals(testValue, clientProperty.Value), "Received value should be the same as sent one");
      });

      Assertion.Assert(secondSendBytes == thirdSendBytes,
        "Sending a single interned object should take the same amount of bytes");
      Assertion.Assert(thirdSendBytes <= firstSendBytes - SumLengths(testValue), "Interning should save data");
    }
  }
}