﻿using JetBrains.Annotations;
using JetBrains.Rd.Reflection;

namespace Test.RdFramework.Reflection
{
  [RdModel]
  public class PropertiesNullOk
  {
    [CanBeNull] public string Prop { get; private set; }
  }
}